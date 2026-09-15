-- 영업상태와 폐업일자가 서로 어긋난다.
--   폐업인데 폐업일자가 없거나 / 영업 중인데 폐업일자가 있다.
-- 원본의 기재 누락이며 좌표는 멀쩡하므로 serving 에는 반영하고 이력만 남긴다.
-- severity: WARN
INSERT INTO validation_result (run_id, rule_code, severity, target_id, detail)
SELECT %(run_id)s, 'STATUS_DATE_CONFLICT', 'WARN', s.source_id,
       jsonb_build_object('status', s.biz_status_name, 'closed_date', s.closed_date)
FROM poi_staging s
WHERE s.run_id = %(run_id)s
  AND (
        (s.biz_status_name NOT IN ('영업/정상', '영업') AND s.closed_date IS NULL)
     OR (s.biz_status_name IN ('영업/정상', '영업')     AND s.closed_date IS NOT NULL)
  );
