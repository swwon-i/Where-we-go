-- 좌표 결측 — 지도에 찍을 수 없고 경로 계산의 출발점이 되지 못한다.
-- ERROR: serving 반영에서 제외한다.
-- severity: ERROR
INSERT INTO validation_result (run_id, rule_code, severity, target_id, detail)
SELECT %(run_id)s, 'COORD_MISSING', 'ERROR', s.source_id,
       jsonb_build_object('name', s.name, 'jibun', s.jibun_address)
FROM poi_staging s
WHERE s.run_id = %(run_id)s AND s.geom IS NULL;
