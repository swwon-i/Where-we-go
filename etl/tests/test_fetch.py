"""원본 받기 — 네트워크 없이. opener 를 바꿔 끼워 서버 응답을 흉내 낸다."""

from __future__ import annotations

import io
import urllib.error
import urllib.parse

import pytest

from etl.fetch import (
    CATEGORY,
    FetchError,
    attachment_name,
    check_header,
    download_url,
    fetch_source,
)
from etl.sources import KOREAN_HEADER, Source

HEADER_LINE = ",".join(KOREAN_HEADER) + "\r\n"


def csv_bytes(rows: int = 3) -> bytes:
    body = HEADER_LINE + "".join(f"3000000,2020-{i},20200101,영업/정상\r\n" for i in range(rows))
    return body.encode("cp949")


class FakeResponse:
    def __init__(self, body: bytes, disposition: str | None, url: str = "https://x/"):
        self._body = io.BytesIO(body)
        self.headers = {"Content-Disposition": disposition} if disposition else {}
        self._url = url

    def read(self, n: int = -1) -> bytes:
        return self._body.read(n)

    def geturl(self) -> str:
        return self._url

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def disposition_for(name: str) -> str:
    return "attachment; filename*=UTF-8''" + urllib.parse.quote(name)


def opener_for(body: bytes, disposition: str | None, calls: list | None = None, quota_status: int = 200):
    def opener(request, timeout=None):
        if calls is not None:
            calls.append((request.full_url, request.get_header("Referer")))
        if request.full_url.endswith("/file/validate/download-count"):
            if quota_status != 200:
                raise urllib.error.HTTPError(request.full_url, quota_status, "", {}, io.BytesIO("잠시후 시도해주세요".encode()))
            return FakeResponse(b"", None)
        return FakeResponse(body, disposition)
    return opener


@pytest.fixture
def source(tmp_path):
    return Source(code="LOCALDATA_FOOD", label="일반음식점",
                  path=tmp_path / "식품_일반음식점_서울특별시.csv")


class TestAttachmentName:
    def test_utf8_filename_star(self):
        assert attachment_name(disposition_for("식품_일반음식점_서울특별시.csv")) == "식품_일반음식점_서울특별시.csv"

    def test_plain_filename(self):
        assert attachment_name('attachment; filename="a.csv"') == "a.csv"

    def test_missing(self):
        assert attachment_name(None) is None


class TestFetchSource:
    def test_replaces_file_and_sends_referer(self, source):
        calls = []
        body = csv_bytes()
        got = fetch_source("food", source, opener_for(body, disposition_for(source.path.name), calls))
        assert source.path.read_bytes() == body
        assert got.new_bytes == len(body) and got.old_bytes is None
        # 사이트 버튼과 같은 순서 — 횟수 확인 먼저, 둘 다 그 업종 안내 페이지를 Referer 로
        assert calls[0][0].endswith("/file/validate/download-count")
        assert calls[1][0] == download_url(CATEGORY["food"])
        assert all(ref.endswith("/file/general_restaurants/info") for _, ref in calls)

    def test_seoul_all_districts(self):
        assert download_url("general_restaurants").endswith("orgCode=6110000_ALL")

    def test_error_page_keeps_old_file(self, source):
        """Referer 가 틀리면 서버는 /error.html 로 보낸다. 첨부가 아니면 기존 파일을 지키고 실패한다."""
        source.path.write_bytes(b"old")
        with pytest.raises(FetchError, match="첨부 파일이 아니"):
            fetch_source("food", source, opener_for(b"<html>error</html>", None))
        assert source.path.read_bytes() == b"old"
        assert not source.path.with_name(source.path.name + ".part").exists()

    def test_other_file_is_rejected(self, source):
        with pytest.raises(FetchError):
            fetch_source("food", source, opener_for(csv_bytes(), disposition_for("식품_휴게음식점_서울특별시.csv")))

    def test_wrong_header_keeps_old_file(self, source):
        source.path.write_bytes(b"old")
        bad = "a,b,c\r\n1,2,3\r\n".encode("cp949")
        with pytest.raises(FetchError, match="헤더"):
            fetch_source("food", source, opener_for(bad, disposition_for(source.path.name)))
        assert source.path.read_bytes() == b"old"

    def test_truncated_download_is_rejected(self, source):
        """기존의 80% 도 안 되면 잘린 것으로 본다. 하루 사이 인허가가 그만큼 줄 일은 없다."""
        source.path.write_bytes(csv_bytes(rows=100))
        before = source.path.read_bytes()
        with pytest.raises(FetchError, match="잘린"):
            fetch_source("food", source, opener_for(csv_bytes(rows=5), disposition_for(source.path.name)))
        assert source.path.read_bytes() == before

    def test_rate_limit_stops_before_download(self, source):
        calls = []
        with pytest.raises(FetchError, match="429"):
            fetch_source("food", source, opener_for(csv_bytes(), disposition_for(source.path.name), calls,
                                                    quota_status=429))
        assert len(calls) == 1          # 파일은 요청하지 않았다


class TestCheckHeader:
    def test_real_header_passes(self, tmp_path):
        p = tmp_path / "a.csv"
        p.write_bytes(csv_bytes())
        check_header(p, "cp949")

    def test_utf8_file_is_rejected(self, tmp_path):
        """인허가 CSV 는 CP949 다. 인코딩이 바뀌면 적재가 깨지므로 여기서 멈춘다."""
        p = tmp_path / "a.csv"
        p.write_bytes(HEADER_LINE.encode("utf-8"))
        with pytest.raises(FetchError):
            check_header(p, "cp949")
