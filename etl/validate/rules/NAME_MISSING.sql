-- 상호명 결측 — 검색도 지도 표시도 불가능하다. serving 의 name 은 NOT NULL 이다.
-- 전체 적재에서 실제로 발견됐다(중구 을지로4가 등). 원본에 사업장명이 비어 있는 행이 존재한다.
-- severity: ERROR
INSERT INTO validation_result (run_id, rule_code, severity, target_id, detail)
SELECT %(run_id)s, 'NAME_MISSING', 'ERROR', s.source_id,
       jsonb_build_object('jibun', s.jibun_address, 'category', s.category_raw)
FROM poi_staging s
WHERE s.run_id = %(run_id)s
  AND (s.name IS NULL OR btrim(s.name) = '');
