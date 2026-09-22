"""ingest 파이프라인의 순수 함수 테스트. DB 없이 돌아간다."""

from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from etl.ingest import (
    OUTSIDE_ALARM_RATIO,
    SEOUL_5186_BOUNDS,
    count_outside_seoul,
    staging_chunk,
    file_hash,
    is_active,
    normalize_category,
    parse_date,
    transform_coords,
    wait_for_db,
)
from etl.sources import KOREAN_HEADER, RAW_COLUMNS, SOURCES


class TestSourcesDefinition:
    def test_column_lists_line_up(self):
        """poi_raw 컬럼과 원본 한글 헤더는 1:1 이어야 한다 — 어긋나면 COPY 가 밀려 들어간다."""
        assert len(RAW_COLUMNS) == len(KOREAN_HEADER) == 39

    def test_raw_column_names_unique(self):
        assert len(set(RAW_COLUMNS)) == len(RAW_COLUMNS)

    def test_sources_have_distinct_codes(self):
        codes = [s.code for s in SOURCES.values()]
        assert len(set(codes)) == len(codes)

    def test_all_sources_are_cp949(self):
        """인허가 CSV 는 전부 CP949 다. UTF-8 로 열면 깨진다."""
        assert all(s.encoding == "cp949" for s in SOURCES.values())


class TestParseDate:
    @pytest.mark.parametrize(
        "raw,expected",
        [
            ("2012-08-29", date(2012, 8, 29)),
            ("20120829", date(2012, 8, 29)),
            ("2008-07-08 09:31:22", date(2008, 7, 8)),
            ("  2012-08-29  ", date(2012, 8, 29)),
        ],
    )
    def test_accepted_formats(self, raw, expected):
        assert parse_date(raw) == expected

    @pytest.mark.parametrize("raw", ["", "   ", None, float("nan"), "없음", "2012-13-45"])
    def test_rejected(self, raw):
        assert parse_date(raw) is None


class TestIsActive:
    @pytest.mark.parametrize("raw", ["영업/정상", "영업", " 영업/정상 "])
    def test_active(self, raw):
        assert is_active(raw)

    @pytest.mark.parametrize("raw", ["폐업", "휴업", "", None, "취소/말소"])
    def test_inactive(self, raw):
        assert not is_active(raw)


class TestNormalizeCategory:
    def test_strips_spaces_and_middots(self):
        assert normalize_category(" 한식 · 분식 ") == "한식분식"

    def test_plain_value_unchanged(self):
        assert normalize_category("커피숍") == "커피숍"

    @pytest.mark.parametrize("raw", ["", "   ", None])
    def test_empty_is_none(self, raw):
        assert normalize_category(raw) is None


class TestTransformCoords:
    def test_known_point(self):
        """종로구 관철동 업소 — 5186 으로 옮기면 서울 중심부에 든다."""
        e, n = transform_coords(
            np.array([198744.767994092]), np.array([451930.939476321])
        )
        assert e[0] == pytest.approx(198814.38, abs=0.1)
        assert n[0] == pytest.approx(552236.12, abs=0.1)

    def test_nan_stays_nan(self):
        """좌표 결측은 결측으로 남아야 한다 — 0 으로 채우면 아프리카 앞바다에 찍힌다."""
        e, n = transform_coords(np.array([np.nan]), np.array([np.nan]))
        assert not np.isfinite(e[0]) and not np.isfinite(n[0])

    def test_vectorized_length(self):
        pts = np.full(100, 198744.767994092)
        e, n = transform_coords(pts, np.full(100, 451930.939476321))
        assert len(e) == len(n) == 100


class TestCountOutsideSeoul:
    def test_inside_counts_zero(self):
        e, n = transform_coords(
            np.array([198744.767994092]), np.array([451930.939476321])
        )
        assert count_outside_seoul(e, n) == 0

    def test_far_point_counted(self):
        assert count_outside_seoul(np.array([0.0]), np.array([0.0])) == 1

    def test_nan_not_counted(self):
        """결측은 COORD_MISSING 이 잡는다. 여기서 이중으로 세지 않는다."""
        assert count_outside_seoul(np.array([np.nan]), np.array([np.nan])) == 0

    def test_bounds_are_a_loose_box(self):
        """실제 행정경계가 아니라 넉넉한 사각형이다 — 서울을 충분히 덮어야 한다."""
        x0, y0, x1, y1 = SEOUL_5186_BOUNDS
        assert x1 - x0 >= 50_000 and y1 - y0 >= 50_000

    def test_alarm_ratio_tolerates_a_handful(self):
        """정상 적재에서 나온 5/538,576 은 경보가 아니어야 한다."""
        assert 5 / 538_576 < OUTSIDE_ALARM_RATIO


def _raw_chunk(rows):
    cols = ["id", "mgmt_no", "biz_name", "biz_type_name", "road_address", "jibun_address",
            "phone", "biz_status_name", "license_date", "closed_date", "coord_x", "coord_y"]
    return pd.DataFrame(rows, columns=cols, dtype=object)


class TestStagingChunk:
    """raw 한 조각 → staging 모양. 조각마다 따로 돌아도 결과가 같아야 한다."""

    ROW = [1, " A-1 ", " 가게 ", "한식", "도로", "지번", "02", "영업/정상", "20200101", None,
           "200000.0", "450000.0"]

    def test_strips_and_parses(self):
        out, _ = staging_chunk(_raw_chunk([self.ROW]))
        r = out.iloc[0]
        assert (r["raw_id"], r["source_id"], r["name"]) == (1, "A-1", "가게")
        assert np.isfinite(r.east) and np.isfinite(r.north)

    def test_rows_without_mgmt_no_are_dropped(self):
        blank = [2, "  ", "x", None, None, None, None, None, None, None, None, None]
        out, _ = staging_chunk(_raw_chunk([self.ROW, blank]))
        assert list(out["source_id"]) == ["A-1"]

    def test_missing_coords_stay_nan(self):
        row = self.ROW[:10] + [None, None]
        out, outside = staging_chunk(_raw_chunk([row]))
        assert np.isnan(out.iloc[0].east) and outside == 0


class TestFileHash:
    def test_stable_for_same_content(self, tmp_path):
        a, b = tmp_path / "a", tmp_path / "b"
        a.write_bytes(b"hello"), b.write_bytes(b"hello")
        assert file_hash(a) == file_hash(b)

    def test_changes_with_content(self, tmp_path):
        p = tmp_path / "f"
        p.write_bytes(b"hello")
        first = file_hash(p)
        p.write_bytes(b"hello!")
        assert file_hash(p) != first

    def test_reads_in_chunks_consistently(self, tmp_path):
        p = tmp_path / "big"
        p.write_bytes(b"x" * 100_000)
        assert file_hash(p, chunk=1024) == file_hash(p, chunk=1 << 20)


class FakeClock:
    """sleep 을 부르면 그만큼 시간이 흐르는 가짜 시계."""

    def __init__(self):
        self.now = 0.0
        self.slept = []

    def __call__(self):
        return self.now

    def sleep(self, sec):
        self.slept.append(sec)
        self.now += sec


def flaky_connect(fail_times):
    """처음 `fail_times` 번은 실패하고 그 뒤로는 닿는 접속 함수."""
    calls = {"n": 0}

    class Conn:
        def close(self):
            pass

    def connect_fn():
        calls["n"] += 1
        if calls["n"] <= fail_times:
            raise ConnectionError("connection refused")
        return Conn()

    return connect_fn


class TestWaitForDb:
    """절전에서 막 깨어나 Docker 가 아직 안 올라온 경우를 기다려 준다."""

    def test_succeeds_after_docker_comes_up(self):
        clock = FakeClock()
        ok, attempts, waited, error = wait_for_db(
            180, 15, connect_fn=flaky_connect(3), sleep=clock.sleep, clock=clock)
        assert ok and attempts == 4 and waited == 45 and error is None

    def test_gives_up_after_wait_budget(self):
        """계속 꺼져 있으면 예산 안에서만 기다리고 물러난다 — 예산을 넘겨 자지 않는다."""
        clock = FakeClock()
        ok, attempts, waited, error = wait_for_db(
            180, 15, connect_fn=flaky_connect(10**6), sleep=clock.sleep, clock=clock)
        assert not ok
        assert isinstance(error, ConnectionError)
        assert waited <= 180
        assert sum(clock.slept) <= 180
        assert attempts == 13  # 0, 15, …, 180초에 시도

    def test_zero_wait_tries_once(self):
        """손으로 돌릴 때(기본 0)는 지금처럼 바로 포기한다."""
        clock = FakeClock()
        ok, attempts, _, _ = wait_for_db(
            0, 15, connect_fn=flaky_connect(1), sleep=clock.sleep, clock=clock)
        assert not ok and attempts == 1 and clock.slept == []


def _db_up() -> bool:
    import socket
    try:
        with socket.create_connection(("localhost", 5432), timeout=0.3):
            return True
    except OSError:
        return False


@pytest.mark.skipif(not _db_up(), reason="DB 가 없으면 건너뛴다")
class TestDiffAgainstPrevious:
    """회차 비교. 실제 DB 에서 두 회차를 만들어 세고, 끝나면 되돌린다."""

    @pytest.fixture
    def conn(self):
        from etl.db import connect
        c = connect()
        yield c
        c.rollback()
        c.close()

    def _run(self, cur, rows):
        cur.execute("INSERT INTO ingest_run (source) VALUES ('TEST_DIFF') RETURNING id")
        run_id = cur.fetchone()[0]
        for source_id, status in rows:
            cur.execute("INSERT INTO poi_staging (run_id, source_id, biz_status_name) VALUES (%s, %s, %s)",
                        (run_id, source_id, status))
        return run_id

    def test_counts_every_kind(self, conn):
        from etl.ingest import diff_against_previous
        with conn.cursor() as cur:
            prev = self._run(cur, [("A", "영업/정상"), ("B", "영업/정상"), ("C", "영업/정상"), ("D", "폐업")])
            cur_ = self._run(cur, [("A", "영업/정상"), ("B", "폐업"), ("D", "폐업"), ("E", "영업/정상")])
        got = diff_against_previous(conn, cur_, prev)
        # A 유지 · B 유지+폐업 전환 · C 소멸 · D 유지(원래 폐업) · E 신규
        assert got == {"new": 1, "kept": 3, "vanished": 1, "closed": 1}

    def test_vanished_is_not_a_closed_transition(self, conn):
        """사라진 것은 소멸이지 폐업 전환이 아니다 — 예전에는 이 칸에 섞여 들어갔다."""
        from etl.ingest import diff_against_previous
        with conn.cursor() as cur:
            prev = self._run(cur, [("A", "영업/정상")])
            cur_ = self._run(cur, [("Z", "영업/정상")])
        got = diff_against_previous(conn, cur_, prev)
        assert got["vanished"] == 1 and got["closed"] == 0

    def test_first_run_has_no_transitions(self, conn):
        from etl.ingest import diff_against_previous
        with conn.cursor() as cur:
            run = self._run(cur, [("A", "폐업")])
        assert diff_against_previous(conn, run, None) == {"new": 1, "kept": 0, "vanished": 0, "closed": 0}


@pytest.mark.skipif(not _db_up(), reason="DB 가 없으면 건너뛴다")
class TestBuildStaging:
    """조각으로 나눠 읽어도 같은 관리번호는 파일에서 마지막 것만 남는다 — 조각 경계를 넘어도."""

    @pytest.fixture
    def conn(self):
        from etl.db import connect
        c = connect()
        yield c
        c.rollback()
        c.close()

    def test_dedupe_keeps_last_across_chunks(self, conn, monkeypatch):
        import etl.ingest as ingest
        monkeypatch.setattr(ingest, "CHUNK_ROWS", 2)     # 네 행을 두 조각으로
        with conn.cursor() as cur:
            cur.execute("INSERT INTO ingest_run (source) VALUES ('TEST_STG') RETURNING id")
            run_id = cur.fetchone()[0]
            for name in ["첫번째", "다른가게", "셋째", "마지막"]:
                mgmt = "B-2" if name == "다른가게" else "A-1"
                cur.execute("INSERT INTO poi_raw (run_id, mgmt_no, biz_name) VALUES (%s, %s, %s)",
                            (run_id, mgmt, name))
        rows, _ = ingest.build_staging(conn, run_id)
        assert rows == 2
        with conn.cursor() as cur:
            cur.execute("SELECT source_id, name FROM poi_staging WHERE run_id = %s ORDER BY 1", (run_id,))
            assert cur.fetchall() == [("A-1", "마지막"), ("B-2", "다른가게")]


@pytest.mark.skipif(not _db_up(), reason="DB 가 없으면 건너뛴다")
class TestPruneHistory:
    """raw 와 검수 기록은 최근 N 회차만. 매일 갱신이면 raw 가 한 달 8GB 쌓인다."""

    @pytest.fixture
    def conn(self):
        from etl.db import connect
        c = connect()
        yield c
        c.rollback()
        c.close()

    def _runs(self, cur, n, with_raw=True):
        ids = []
        for _ in range(n):
            cur.execute("INSERT INTO ingest_run (source) VALUES ('TEST_PRUNE') RETURNING id")
            rid = cur.fetchone()[0]
            if with_raw:
                cur.execute("INSERT INTO poi_raw (run_id, mgmt_no) VALUES (%s, 'x')", (rid,))
                cur.execute("INSERT INTO validation_result (run_id, rule_code, severity, target_id) "
                            "VALUES (%s, 'NAME_MISSING', 'ERROR', 'x')", (rid,))
            ids.append(rid)
        return ids

    def test_keeps_latest_runs(self, conn):
        from etl.ingest import prune_history
        with conn.cursor() as cur:
            ids = self._runs(cur, 5)
            assert prune_history(conn, "TEST_PRUNE", raw_keep=2, validation_keep=3) == (3, 2)
            cur.execute("SELECT run_id FROM poi_raw WHERE run_id = ANY(%s) ORDER BY 1", (ids,))
            assert [r[0] for r in cur.fetchall()] == ids[-2:]
            cur.execute("SELECT count(*) FROM ingest_run WHERE id = ANY(%s)", (ids,))
            assert cur.fetchone()[0] == 5       # 회차 기록은 지우지 않는다

    def test_skipped_runs_do_not_shorten_retention(self, conn):
        """SKIPPED 회차에는 raw 가 없다. 회차 번호로 세면 건너뛴 날마다 보관분이 줄어든다."""
        from etl.ingest import prune_history
        with conn.cursor() as cur:
            ids = self._runs(cur, 2)
            self._runs(cur, 3, with_raw=False)
            assert prune_history(conn, "TEST_PRUNE", raw_keep=2, validation_keep=2) == (0, 0)
