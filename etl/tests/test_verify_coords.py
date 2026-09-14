"""verify_coords의 순수 함수 테스트.

원본 CSV(340MB)에 의존하지 않는다 — CSV가 필요한 테스트는 CP949 픽스처를 직접 만든다.
"""

from __future__ import annotations

import pandas as pd
import pytest

from etl.verify_coords import (
    THRESHOLD_PASS,
    THRESHOLD_WARN,
    Anchor,
    evaluate_candidate,
    haversine_m,
    in_seoul,
    load_coords,
    make_transformer,
    match_anchor,
    pick_best,
    verdict,
)

# 서울시청 / 강남역 (WGS84). 실제 직선거리는 약 8.8km.
CITY_HALL = (37.5663, 126.9779)
GANGNAM = (37.4979, 127.0276)


class TestHaversine:
    def test_same_point_is_zero(self):
        assert haversine_m(*CITY_HALL, *CITY_HALL) == pytest.approx(0.0, abs=1e-6)

    def test_known_distance(self):
        d = haversine_m(*CITY_HALL, *GANGNAM)
        assert 8_500 < d < 9_100, f"시청~강남역이 {d:.0f}m로 나왔다"

    def test_symmetric(self):
        assert haversine_m(*CITY_HALL, *GANGNAM) == pytest.approx(
            haversine_m(*GANGNAM, *CITY_HALL)
        )

    def test_one_degree_latitude_is_about_111km(self):
        d = haversine_m(37.0, 127.0, 38.0, 127.0)
        assert 110_500 < d < 111_500


class TestInSeoul:
    def test_city_hall_inside(self):
        assert in_seoul(CITY_HALL[1], CITY_HALL[0])

    def test_busan_outside(self):
        assert not in_seoul(129.0756, 35.1796)

    @pytest.mark.parametrize("lon,lat", [(0.0, 0.0), (127.0, 0.0), (0.0, 37.5)])
    def test_null_island_and_axis_swaps_outside(self, lon, lat):
        """위경도를 뒤바꾸거나 좌표가 0으로 깨진 경우를 걸러낸다."""
        assert not in_seoul(lon, lat)


class TestVerdict:
    def test_no_data(self):
        assert verdict(None) == "NO_DATA"

    @pytest.mark.parametrize("m", [0.0, 10.0, THRESHOLD_PASS - 0.1])
    def test_pass(self, m):
        assert verdict(m) == "PASS"

    @pytest.mark.parametrize("m", [THRESHOLD_PASS, 200.0, THRESHOLD_WARN - 0.1])
    def test_warn(self, m):
        assert verdict(m) == "WARN"

    @pytest.mark.parametrize("m", [THRESHOLD_WARN, 400.0, 10_000.0])
    def test_fail(self, m):
        assert verdict(m) == "FAIL"


class TestTransformer:
    def test_5174_sample_lands_in_seoul(self):
        """실제 데이터에서 가져온 종로구 업소 좌표 한 건."""
        lon, lat = make_transformer("EPSG:5174").transform(198744.767994092, 451930.939476321)
        assert in_seoul(lon, lat)

    def test_candidates_differ_measurably(self):
        """5174와 2097의 lon_0 차이(약 10.4초)는 200m 이상으로 나타나야 한다.

        차이가 없다면 후보를 나눠 검증할 이유가 없다는 뜻이므로, 이 테스트가 실패하면
        CRS_CANDIDATES 정의를 의심해야 한다.
        """
        x, y = 198744.767994092, 451930.939476321
        lon_a, lat_a = make_transformer("EPSG:5174").transform(x, y)
        lon_b, lat_b = make_transformer("EPSG:2097").transform(x, y)
        assert haversine_m(lat_a, lon_a, lat_b, lon_b) > 200


class TestMatchAnchor:
    @pytest.fixture
    def df(self):
        return pd.DataFrame(
            {
                "name": ["가", "나", "다", "라"],
                "jibun": [
                    "서울특별시 송파구 신천동 29 ",
                    "서울특별시 송파구 신천동 293 ",
                    "서울특별시 송파구 신천동 29-1 ",
                    "서울특별시 강남구 삼성동 159 ",
                ],
                "x": [1.0, 2.0, 3.0, 4.0],
                "y": [1.0, 2.0, 3.0, 4.0],
            }
        )

    def test_excludes_longer_lot_number(self, df):
        """`신천동 29`가 `신천동 293`을 잡으면 안 된다."""
        hits = match_anchor(df, Anchor("t", r"송파구 신천동 29", 37.5, 127.1))
        assert set(hits["name"]) == {"가", "다"}

    def test_no_match_returns_empty(self, df):
        hits = match_anchor(df, Anchor("t", r"없는구 없는동 1", 37.5, 127.1))
        assert hits.empty


class TestLoadCoords:
    @pytest.fixture
    def csv_path(self, tmp_path):
        """CP949 인코딩 + 좌표 뒤 공백 + 결측 행을 가진 축소 픽스처."""
        rows = [
            "사업장명,지번주소,좌표정보(X),좌표정보(Y)",
            "가게1,서울특별시 종로구 신문로1가 14-3 ,198744.767994092    ,451930.939476321    ",
            "가게2,서울특별시 종로구 관철동 80-31 ,,",  # 좌표 결측 → 제외
            "가게3,서울특별시 종로구 안국동 163 ,199325.417035129    ,452073.537172485    ",
        ]
        p = tmp_path / "sample.csv"
        p.write_text("\n".join(rows), encoding="cp949")
        return p

    def test_drops_rows_without_coords(self, csv_path):
        df = load_coords(csv_path)
        assert len(df) == 2
        assert set(df["name"]) == {"가게1", "가게3"}

    def test_strips_trailing_spaces_and_casts(self, csv_path):
        df = load_coords(csv_path)
        assert df["x"].dtype.kind == "f"
        assert df.loc[0, "x"] == pytest.approx(198744.767994092)

    def test_nrows_limit(self, csv_path):
        assert len(load_coords(csv_path, nrows=1)) == 1

    def test_columns_are_normalized(self, csv_path):
        assert list(load_coords(csv_path).columns) == ["name", "jibun", "x", "y"]


class TestEvaluateAndPick:
    @pytest.fixture
    def df(self):
        return pd.DataFrame(
            {
                "name": ["가게1"],
                "jibun": ["서울특별시 종로구 신문로1가 14-3 "],
                "x": [198744.767994092],
                "y": [451930.939476321],
            }
        )

    def test_correct_crs_beats_negative_control(self, df):
        """5186을 원본으로 잘못 가정하면 오차가 크게 벌어져야 한다.

        검사가 판별력을 갖는지 확인하는 테스트다 — 두 결과가 비슷하게 나오면
        이 스크립트로는 좌표계를 고를 수 없다는 뜻이다.
        """
        anchor = Anchor("신문로1가 14-3", r"종로구 신문로1가 14-3", 37.5709, 126.9705)
        good = evaluate_candidate(df, "5174", "EPSG:5174", [anchor])
        bad = evaluate_candidate(df, "[음성대조군] 5186", "EPSG:5186", [anchor])
        assert good["median_error_m"] < bad["median_error_m"]
        assert bad["verdict"] == "FAIL"

    def test_missing_anchor_yields_none(self, df):
        anchor = Anchor("없음", r"없는구 없는동 1", 37.5, 127.0)
        result = evaluate_candidate(df, "5174", "EPSG:5174", [anchor])
        assert result["median_error_m"] is None
        assert result["verdict"] == "NO_DATA"
        assert result["per_anchor"][0]["samples"] == 0

    def test_pick_best_ignores_negative_control(self):
        results = [
            {"label": "[음성대조군] X", "median_error_m": 1.0},
            {"label": "후보 A", "median_error_m": 50.0},
            {"label": "후보 B", "median_error_m": 400.0},
        ]
        assert pick_best(results)["label"] == "후보 A"

    def test_pick_best_returns_none_when_nothing_scored(self):
        assert pick_best([{"label": "후보 A", "median_error_m": None}]) is None
