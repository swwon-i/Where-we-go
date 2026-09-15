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
    dedupe_by_source_id,
    file_hash,
    is_active,
    normalize_category,
    parse_date,
    transform_coords,
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


class TestDedupe:
    def test_keeps_last_occurrence(self):
        df = pd.DataFrame({"source_id": ["a", "b", "a"], "name": ["1", "2", "3"]})
        out = dedupe_by_source_id(df)
        assert len(out) == 2
        assert out[out["source_id"] == "a"]["name"].item() == "3"

    def test_no_duplicates_unchanged(self):
        df = pd.DataFrame({"source_id": ["a", "b"], "name": ["1", "2"]})
        assert len(dedupe_by_source_id(df)) == 2


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
