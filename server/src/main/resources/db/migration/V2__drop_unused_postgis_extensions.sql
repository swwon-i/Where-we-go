-- V2 — 쓰지 않는 PostGIS 부가 확장 제거
--
-- postgis/postgis 이미지는 initdb 단계에서 postgis 외에 postgis_topology 와
-- postgis_tiger_geocoder 까지 자동으로 설치한다. tiger 는 미국 인구조사국 주소 지오코더로
-- 이 프로젝트와 무관하며 테이블 40개를 만든다. 스키마를 읽는 사람에게 노이즈이고
-- 덤프 용량도 늘어나므로 제거한다.
--
-- fuzzystrmatch 는 남긴다 — levenshtein/soundex 가 역명·상호명 매칭에 쓰일 수 있다.

DROP EXTENSION IF EXISTS postgis_tiger_geocoder CASCADE;
DROP EXTENSION IF EXISTS postgis_topology CASCADE;

DROP SCHEMA IF EXISTS tiger_data CASCADE;
DROP SCHEMA IF EXISTS tiger CASCADE;
DROP SCHEMA IF EXISTS topology CASCADE;
