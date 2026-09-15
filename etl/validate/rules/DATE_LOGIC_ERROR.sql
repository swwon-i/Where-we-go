-- 인허가일자가 폐업일자보다 늦다 — 시간 순서가 성립하지 않는다.
-- severity: WARN
INSERT INTO validation_result (run_id, rule_code, severity, target_id, detail)
SELECT %(run_id)s, 'DATE_LOGIC_ERROR', 'WARN', s.source_id,
       jsonb_build_object('licensed', s.licensed_date, 'closed', s.closed_date)
FROM poi_staging s
WHERE s.run_id = %(run_id)s
  AND s.licensed_date IS NOT NULL
  AND s.closed_date   IS NOT NULL
  AND s.licensed_date > s.closed_date;
