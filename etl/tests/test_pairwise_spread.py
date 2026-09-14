"""pairwise_spread 테스트 — "선택이 결과를 바꾸는가"를 재는 함수."""

from __future__ import annotations

import pandas as pd
import pytest

from etl.verify_coords import CRS_CANDIDATES, pairwise_spread

LABEL_5174 = "EPSG:5174 (PROJ 기본값)"
LABEL_2097 = "EPSG:2097 (보정 미적용)"
LABEL_3P = "Bessel 보정 + towgs84 3p"


@pytest.fixture
def df():
    """서울 곳곳의 5174 좌표 몇 건."""
    return pd.DataFrame(
        {
            "x": [198744.767994092, 199325.417035129, 197804.357783157, 199670.979884558],
            "y": [451930.939476321, 452073.537172485, 452216.539274871, 452221.775006084],
        }
    )


def test_labels_must_exist_in_candidates():
    """테스트가 쓰는 라벨이 실제 후보 표에 있는지 — 오타로 조용히 통과하는 것을 막는다."""
    for label in (LABEL_5174, LABEL_2097, LABEL_3P):
        assert label in CRS_CANDIDATES


def test_single_candidate_yields_no_pairs(df):
    assert pairwise_spread(df, [LABEL_5174]) == []


def test_pair_count_is_combinatorial(df):
    assert len(pairwise_spread(df, [LABEL_5174, LABEL_2097, LABEL_3P])) == 3


def test_lon0_difference_shows_up_as_hundreds_of_meters(df):
    """보정 적용(5174)과 미적용(2097)은 200m 이상 벌어져야 한다."""
    (result,) = pairwise_spread(df, [LABEL_5174, LABEL_2097])
    assert result["median_m"] > 200


def test_datum_variants_are_close(df):
    """같은 lon_0에서 datum shift 방식만 다른 후보끼리는 수십 미터 안쪽이어야 한다.

    이 값이 크게 나오면 towgs84 선택이 결과를 좌우한다는 뜻이므로 앵커를 늘려
    어느 쪽인지 가려야 한다.
    """
    (result,) = pairwise_spread(df, [LABEL_5174, LABEL_3P])
    assert result["median_m"] < 100


def test_max_is_not_less_than_median(df):
    for result in pairwise_spread(df, [LABEL_5174, LABEL_2097, LABEL_3P]):
        assert result["max_m"] >= result["median_m"]


def test_sampling_is_deterministic(df):
    assert pairwise_spread(df, [LABEL_5174, LABEL_2097], sample_n=2, seed=7) == (
        pairwise_spread(df, [LABEL_5174, LABEL_2097], sample_n=2, seed=7)
    )


def test_sample_larger_than_frame_is_safe(df):
    """표본 요청이 행 수보다 커도 예외 없이 동작해야 한다."""
    assert pairwise_spread(df, [LABEL_5174, LABEL_2097], sample_n=10_000)
