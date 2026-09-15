"""verify_db_crs 테스트 — DB 없이 돌아가는 부분만 다룬다.

DB 왕복 비교 자체는 `python -m etl.verify_db_crs` 로 확인한다(컨테이너가 떠 있어야 함).
"""

from __future__ import annotations

import numpy as np
import pytest

from etl.verify_db_crs import (
    DEFAULT_TOLERANCE_M,
    DST_EPSG,
    SEOUL_5174_BOUNDS,
    SRC_EPSG,
    compare,
    sample_points,
    transform_with_pyproj,
    verdict,
)


class TestSamplePoints:
    def test_shape(self):
        assert sample_points(25).shape == (25, 2)

    def test_within_bounds(self):
        x0, y0, x1, y1 = SEOUL_5174_BOUNDS
        pts = sample_points(500)
        assert (pts[:, 0] >= x0).all() and (pts[:, 0] <= x1).all()
        assert (pts[:, 1] >= y0).all() and (pts[:, 1] <= y1).all()

    def test_deterministic_for_same_seed(self):
        assert np.array_equal(sample_points(10, seed=3), sample_points(10, seed=3))

    def test_different_seeds_differ(self):
        assert not np.array_equal(sample_points(10, seed=1), sample_points(10, seed=2))


class TestTransform:
    def test_lands_in_5186_seoul_range(self):
        """5186 중부원점 기준 서울은 동거 약 18~22만, 북거 약 53~57만이다."""
        out = transform_with_pyproj(sample_points(200))
        assert (out[:, 0] > 170_000).all() and (out[:, 0] < 230_000).all()
        assert (out[:, 1] > 520_000).all() and (out[:, 1] < 580_000).all()

    def test_northing_shifts_by_false_origin(self):
        """5174는 false northing 500000, 5186은 600000이라 북거가 크게 올라간다."""
        pts = sample_points(50)
        out = transform_with_pyproj(pts)
        assert (out[:, 1] - pts[:, 1] > 90_000).all()

    def test_epsg_constants(self):
        assert (SRC_EPSG, DST_EPSG) == (5174, 5186)


class TestCompare:
    def test_identical_is_zero(self):
        a = sample_points(20)
        s = compare(a, a.copy())
        assert s["max_m"] == pytest.approx(0.0)
        assert s["count"] == 20

    def test_known_offset(self):
        """한 점만 3-4-5 만큼 밀면 최대 5m, 중앙은 0m이어야 한다."""
        a = np.zeros((5, 2))
        b = a.copy()
        b[0] = (3.0, 4.0)
        s = compare(a, b)
        assert s["max_m"] == pytest.approx(5.0)
        assert s["median_m"] == pytest.approx(0.0)

    def test_stats_ordering(self):
        rng = np.random.default_rng(0)
        a = sample_points(100)
        b = a + rng.normal(scale=0.5, size=a.shape)
        s = compare(a, b)
        assert s["median_m"] <= s["max_m"]
        assert s["mean_m"] <= s["max_m"]


class TestVerdict:
    def test_passes_within_tolerance(self):
        assert verdict({"max_m": 0.0005}, DEFAULT_TOLERANCE_M)

    def test_boundary_is_inclusive(self):
        assert verdict({"max_m": DEFAULT_TOLERANCE_M}, DEFAULT_TOLERANCE_M)

    def test_fails_outside(self):
        assert not verdict({"max_m": 21.3}, DEFAULT_TOLERANCE_M)

    def test_tolerance_is_tight(self):
        """허용치가 느슨해지면 엔진 불일치를 놓친다 — 1mm 이하로 유지할 것."""
        assert DEFAULT_TOLERANCE_M <= 0.001
