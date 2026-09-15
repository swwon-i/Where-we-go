-- 직전 회차와 좌표가 10m 넘게 다르다.
-- 원본 정정일 수도 있고 좌표 변환이 틀어진 신호일 수도 있다 — 후자면 대량으로 걸린다.
-- 직전 회차가 없으면(최초 적재) 아무것도 걸리지 않는다.
-- severity: WARN
INSERT INTO validation_result (run_id, rule_code, severity, target_id, detail)
SELECT %(run_id)s, 'COORD_MOVED', 'WARN', cur.source_id,
       jsonb_build_object('moved_m', round(ST_Distance(cur.geom, prev.geom)::numeric, 1))
FROM poi_staging cur
JOIN poi_staging prev
  ON prev.run_id = %(prev_run_id)s AND prev.source_id = cur.source_id
WHERE cur.run_id = %(run_id)s
  AND cur.geom IS NOT NULL AND prev.geom IS NOT NULL
  AND ST_Distance(cur.geom, prev.geom) > 10;
