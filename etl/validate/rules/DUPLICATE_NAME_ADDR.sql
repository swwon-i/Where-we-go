-- 같은 상호 + 같은 지번주소가 두 건 이상이다.
-- 실제 중복 등록일 수도, 한 건물의 층별 영업장일 수도 있어 판단하지 않고 기록만 한다.
-- severity: WARN
INSERT INTO validation_result (run_id, rule_code, severity, target_id, detail)
SELECT %(run_id)s, 'DUPLICATE_NAME_ADDR', 'WARN', s.source_id,
       jsonb_build_object('name', s.name, 'jibun', s.jibun_address, 'group_size', d.cnt)
FROM poi_staging s
JOIN (
    SELECT name, jibun_address, count(*) AS cnt
    FROM poi_staging
    WHERE run_id = %(run_id)s AND name IS NOT NULL AND jibun_address IS NOT NULL
    GROUP BY name, jibun_address
    HAVING count(*) > 1
) d ON d.name = s.name AND d.jibun_address = s.jibun_address
WHERE s.run_id = %(run_id)s;
