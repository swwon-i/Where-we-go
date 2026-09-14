"""verify_crs_alignment 테스트 — POI(5174)와 정류장(4326)의 5186 정합 검증."""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from etl.verify_crs_alignment import (
    ALIGNED_MEDIAN_M,
    TARGET_CRS,
    evaluate_alignment,
    load_stops,
    nearest_distances,
)

# 종로2가사거리 정류장 (정류장마스터 실제 값)
JONGNO_LAT, JONGNO_LON = 37.5698055407, 126.9877522923


class TestNearestDistances:
    def test_exact_overlap_is_zero(self):
        p = np.array([0.0, 10.0])
        d = nearest_distances(p, p, p, p)
        assert np.allclose(d, 0.0)

    def test_picks_the_closer_of_two(self):
        d = nearest_distances(
            np.array([0.0]), np.array([0.0]),
            np.array([3.0, 100.0]), np.array([4.0, 100.0]),
        )
        assert d[0] == pytest.approx(5.0)  # 3-4-5 삼각형

    def test_chunking_does_not_change_result(self):
        rng = np.random.default_rng(0)
        px, py = rng.normal(size=50), rng.normal(size=50)
        qx, qy = rng.normal(size=30), rng.normal(size=30)
        assert np.allclose(
            nearest_distances(px, py, qx, qy, chunk=7),
            nearest_distances(px, py, qx, qy, chunk=1000),
        )

    def test_output_length_matches_input(self):
        rng = np.random.default_rng(1)
        px, py = rng.normal(size=13), rng.normal(size=13)
        qx, qy = rng.normal(size=5), rng.normal(size=5)
        assert len(nearest_distances(px, py, qx, qy, chunk=4)) == 13


class TestLoadStops:
    @pytest.fixture
    def csv_path(self, tmp_path):
        rows = [
            '"정류장_ID","정류장_명칭","위도","경도"',
            f'"100000001","종로2가사거리","{JONGNO_LAT}","{JONGNO_LON}"',
            '"100000002","좌표없음","",""',
        ]
        p = tmp_path / "stops.csv"
        p.write_text("\n".join(rows), encoding="cp949")
        return p

    def test_drops_rows_without_coords(self, csv_path):
        assert len(load_stops(csv_path)) == 1

    def test_projects_into_target_crs(self, csv_path):
        """5186 중부원점: 서울은 동거 약 19~21만, 북거 약 54~56만 범위에 든다."""
        stops = load_stops(csv_path)
        assert 180_000 < stops.loc[0, "east"] < 220_000
        assert 530_000 < stops.loc[0, "north"] < 570_000

    def test_columns(self, csv_path):
        assert list(load_stops(csv_path).columns) == ["east", "north"]


class TestEvaluateAlignment:
    @pytest.fixture
    def stops(self):
        """종로2가사거리 정류장 하나만 둔 기준 집합.

        정류장마스터의 위경도(126.9877522923, 37.5698055407)를 5186으로 옮긴 값이다.
        손으로 어림한 값을 쓰면 이 테스트가 거짓 실패한다 — 실제 변환 결과를 넣을 것.
        """
        return pd.DataFrame({"east": [198_918.0], "north": [552_251.8]})

    @pytest.fixture
    def poi(self):
        """종로구 관철동 업소 — 위 정류장에서 약 105m (5174 원본 좌표)."""
        return pd.DataFrame({"x": [198744.767994092], "y": [451930.939476321]})

    def test_realistic_walking_distance(self, poi, stops):
        """관철동 업소 ↔ 종로2가사거리 정류장은 걸어서 갈 거리여야 한다.

        POI(5174)와 정류장(4326)을 5186으로 통일했을 때 실제로 정합하는지를 보는,
        이 모듈의 핵심 주장에 해당하는 테스트다.
        """
        r = evaluate_alignment(poi, stops, "5174", "EPSG:5174")
        assert 50 < r["median_m"] < 200

    def test_correct_crs_beats_negative_control(self, poi, stops):
        good = evaluate_alignment(poi, stops, "5174", "EPSG:5174")
        bad = evaluate_alignment(poi, stops, "[음성대조군] 5186", TARGET_CRS)
        assert good["median_m"] < bad["median_m"]
        assert good["aligned"] and not bad["aligned"]

    def test_aligned_flag_follows_threshold(self, poi, stops):
        result = evaluate_alignment(poi, stops, "5174", "EPSG:5174")
        assert result["aligned"] == (result["median_m"] < ALIGNED_MEDIAN_M)

    def test_percentiles_are_ordered(self, poi, stops):
        r = evaluate_alignment(poi, stops, "5174", "EPSG:5174")
        assert r["p25_m"] <= r["median_m"] <= r["p75_m"] <= r["p95_m"]

    def test_ratio_is_a_fraction(self, poi, stops):
        r = evaluate_alignment(poi, stops, "5174", "EPSG:5174")
        assert 0.0 <= r["within_100m_ratio"] <= 1.0
