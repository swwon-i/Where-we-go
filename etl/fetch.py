"""인허가 원본 CSV 를 받아 온다. 일일 적재(`run_daily.cmd`)가 적재 앞에서 부른다.

    file.localdata.go.kr ──▶ csv/장소/*.csv.part ──(검사)──▶ csv/장소/*.csv ──▶ etl.ingest

LOCALDATA 는 2026-04 에 공공데이터포털로 이전됐고, 공공데이터포털의 데이터셋 페이지
(「행정안전부_일반음식점」 15045016 등)가 안내하는 제공 방식이 이 파일 서버 직접 다운로드다.
오픈API 는 없다. 매일 2일 전 기준으로 갱신되고 이용허락범위는 제한 없음이다.
**API 키가 필요 없다** — 클론 후 바로 실행된다는 원칙이 그대로 선다.

받아 오기만 한다. 바뀌었는지는 `etl.ingest` 가 파일 해시로 판정한다 — 같으면 SKIPPED.

서버의 규칙 두 가지를 따른다.

- **Referer** — 없으면 오류 페이지로 보낸다. 사이트의 다운로드 버튼이 보내는 것과 같은 값
  (그 업종의 안내 페이지)을 붙인다
- **다운로드 횟수 제한** — 버튼은 받기 전에 `/file/validate/download-count` 를 묻고 429 면
  멈춘다. 여기서도 같은 순서를 밟는다. 하루 두 파일이다

**받다가 실패해도 기존 파일은 그대로다.** `.part` 로 받아 검사를 통과한 것만 제자리로 옮긴다.
검사는 셋 — 서버가 준 파일 이름, CSV 헤더(39컬럼), 크기(기존의 80% 미만이면 잘린 것으로 본다).

사용법
-----
    python -m etl.fetch --source all
"""

from __future__ import annotations

import argparse
import csv
import io
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from etl.sources import KOREAN_HEADER, SOURCES, Source

BASE_URL = "https://file.localdata.go.kr"

#: 파일 서버의 업종 식별자. 사이트 주소 `/file/{업종}/info` 의 그 자리다.
CATEGORY = {"food": "general_restaurants", "rest": "rest_cafes"}

#: 지역 코드. 서울특별시 본청 + 구 전체. `6110000` 만 쓰면 본청 소관만 온다.
SEOUL = "6110000_ALL"

#: 새 파일이 기존의 이 비율보다 작으면 잘린 것으로 본다. 하루 사이 인허가가 20% 줄 일은 없다.
MIN_SIZE_RATIO = 0.8

CHUNK = 1 << 20
TIMEOUT_SEC = 300
USER_AGENT = "Where-We-Go-daily-fetch/1.0 (+https://github.com/swwon-i/Where-we-go)"

#: 받는 단계가 실패했다. 적재는 기존 파일로 계속할 수 있으므로 스케줄러가 구분하게 한다.
EXIT_FETCH_FAILED = 4


class FetchError(RuntimeError):
    pass


@dataclass(frozen=True)
class Fetched:
    source: str
    path: Path
    old_bytes: int | None
    new_bytes: int
    seconds: float


def info_url(category: str) -> str:
    return f"{BASE_URL}/file/{category}/info"


def download_url(category: str, org: str = SEOUL) -> str:
    return f"{BASE_URL}/file/download/{category}/info?orgCode={urllib.parse.quote(org)}"


def _request(url: str, referer: str) -> urllib.request.Request:
    return urllib.request.Request(url, headers={"Referer": referer, "User-Agent": USER_AGENT})


def attachment_name(disposition: str | None) -> str | None:
    """`Content-Disposition` 의 파일 이름. `filename*=UTF-8''…` 을 먼저 본다."""
    if not disposition:
        return None
    for part in disposition.split(";"):
        key, _, value = part.strip().partition("=")
        if key.lower() == "filename*" and "''" in value:
            return urllib.parse.unquote(value.split("''", 1)[1])
    for part in disposition.split(";"):
        key, _, value = part.strip().partition("=")
        if key.lower() == "filename":
            return value.strip('"')
    return None


def check_header(path: Path, encoding: str) -> None:
    """첫 줄이 인허가 39컬럼 헤더인가. 오류 페이지나 다른 업종 파일을 여기서 걸러낸다."""
    with open(path, "rb") as f:
        first = f.readline()
    try:
        line = first.decode(encoding)
    except UnicodeDecodeError as e:
        raise FetchError(f"{path.name}: 첫 줄이 {encoding} 가 아니다 ({e})") from e
    header = next(csv.reader(io.StringIO(line)))
    if [h.strip() for h in header] != KOREAN_HEADER:
        raise FetchError(f"{path.name}: 헤더가 다르다 — {len(header)}컬럼, 앞부분 {header[:3]}")


def check_size(new_bytes: int, old_bytes: int | None) -> None:
    if old_bytes and new_bytes < old_bytes * MIN_SIZE_RATIO:
        raise FetchError(f"기존 {old_bytes:,}B 의 {new_bytes / old_bytes:.0%} 뿐이다 — 잘린 것으로 본다")


def validate_quota(category: str, opener=urllib.request.urlopen) -> None:
    """사이트 버튼과 같은 순서 — 받기 전에 다운로드 횟수를 묻는다."""
    try:
        with opener(_request(f"{BASE_URL}/file/validate/download-count", info_url(category)),
                    timeout=30) as r:
            r.read()
    except urllib.error.HTTPError as e:
        if e.code == 429:
            raise FetchError(f"다운로드 횟수 제한(429): {e.read().decode('utf-8', 'replace')[:100]}") from e
        raise FetchError(f"다운로드 횟수 확인 실패: HTTP {e.code}") from e


def fetch_source(key: str, source: Source, opener=urllib.request.urlopen) -> Fetched:
    """한 원천을 받아 검사하고 제자리로 옮긴다. 실패하면 기존 파일을 건드리지 않는다."""
    category = CATEGORY[key]
    validate_quota(category, opener)

    target = source.path
    part = target.with_name(target.name + ".part")
    old_bytes = target.stat().st_size if target.exists() else None
    target.parent.mkdir(parents=True, exist_ok=True)

    t0 = time.monotonic()
    try:
        try:
            response = opener(_request(download_url(category), info_url(category)), timeout=TIMEOUT_SEC)
        except urllib.error.HTTPError as e:
            raise FetchError(f"HTTP {e.code}") from e
        except urllib.error.URLError as e:
            raise FetchError(f"접속 실패: {e.reason}") from e
        with response:
            # Referer 가 없거나 틀리면 302 → /error.html 이 된다. urllib 은 따라가서 HTML 을 준다
            name = attachment_name(response.headers.get("Content-Disposition"))
            if name != target.name:
                raise FetchError(f"첨부 파일이 아니거나 다른 파일이다: {name!r} (최종 주소 {response.geturl()})")
            with open(part, "wb") as out:
                while chunk := response.read(CHUNK):
                    out.write(chunk)

        new_bytes = part.stat().st_size
        check_header(part, source.encoding)
        check_size(new_bytes, old_bytes)
        os.replace(part, target)
    finally:
        if part.exists():
            part.unlink()

    return Fetched(source.code, target, old_bytes, new_bytes, time.monotonic() - t0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="인허가 원본 CSV 받기")
    parser.add_argument("--source", choices=[*SOURCES, "all"], default="all")
    args = parser.parse_args(argv)

    keys = list(SOURCES) if args.source == "all" else [args.source]
    failed = 0
    for key in keys:
        source = SOURCES[key]
        print(f"── {source.label} ({source.code})", flush=True)
        try:
            got = fetch_source(key, source)
        except FetchError as e:
            failed += 1
            print(f"   받기 실패 — 기존 파일로 적재한다: {e}", flush=True)
            continue
        old = f"{got.old_bytes:,}B" if got.old_bytes else "없음"
        print(f"   {old} → {got.new_bytes:,}B · {got.seconds:.1f}초", flush=True)
    return EXIT_FETCH_FAILED if failed else 0


if __name__ == "__main__":
    sys.exit(main())
