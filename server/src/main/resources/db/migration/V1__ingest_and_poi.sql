-- V1 — 적재 회차 · raw/staging/serving 3계층 · 검수 이력
--
-- 설계 원칙 (계획서 §7)
--   * raw는 제약을 걸지 않는다. 원본 중복 1건이 COPY 전체를 죽이면 "실패 격리"가 성립하지 않는다.
--   * serving은 항상 UPSERT. 테이블 전체 교체(swap)를 쓰지 않는다 —
--     전체분 재적재가 일 변동분으로 갱신한 영업상태를 덮어쓰기 때문이다.
--   * 폐업은 삭제가 아니라 status 변경(소프트 삭제). 소멸(파일에서 사라짐)은 별개 사건으로 기록한다.
--   * 좌표는 5186으로 저장한다. 5174 → 5186 변환은 **ETL(pyproj)에서만** 수행하고
--     DB는 변환하지 않는다 — 두 엔진이 EPSG:5174의 datum shift를 다르게 해석할 수 있기 때문이다.
--     (D0 검증 결과: 해석 차이는 최대 21.3m. 계획서 §6 참조)

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;


-- ─────────────────────────────────────────────────────────────────────────────
-- 운영 — 적재 회차
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE ingest_run (
    id              BIGSERIAL PRIMARY KEY,
    source          TEXT        NOT NULL,
    snapshot_date   DATE,
    mode            TEXT        NOT NULL DEFAULT 'FULL'
                                CHECK (mode IN ('FULL', 'DELTA')),
    status          TEXT        NOT NULL DEFAULT 'RUNNING'
                                CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),

    -- 변경 감지: 조건부 요청이 안 되는 서버를 대비해 해시도 함께 남긴다
    source_etag     TEXT,
    source_modified TEXT,
    source_hash     TEXT,

    -- 건수. 신규/유지/소멸은 직전 회차와의 diff에서만 채워진다 (계획서 §6 증분 갱신 2층 구조)
    rows_total      INTEGER,
    rows_valid      INTEGER,
    rows_rejected   INTEGER,
    rows_new        INTEGER,
    rows_kept       INTEGER,
    rows_vanished   INTEGER,
    rows_closed     INTEGER,

    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,
    error_message   TEXT
);

COMMENT ON TABLE  ingest_run IS '적재 회차별 한 행. /admin/ingest-runs 와 README 표의 원천';
COMMENT ON COLUMN ingest_run.rows_vanished IS '원본 파일에서 레코드 자체가 사라진 건수. 폐업(rows_closed)과 다른 사건이다';
COMMENT ON COLUMN ingest_run.rows_rejected IS 'severity=ERROR 검수에 걸려 serving 반영에서 제외된 건수';

CREATE INDEX ix_ingest_run_source_started ON ingest_run (source, started_at DESC);


-- ─────────────────────────────────────────────────────────────────────────────
-- raw — 가공하지 않고 보관. 제약을 걸지 않는다.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE poi_raw (
    id                      BIGSERIAL PRIMARY KEY,
    run_id                  BIGINT NOT NULL REFERENCES ingest_run (id),

    -- 인허가 CSV 39컬럼을 순서 그대로, 전부 text NULL 허용으로 받는다
    open_local_gov_code     TEXT,   -- 개방자치단체코드
    mgmt_no                 TEXT,   -- 관리번호
    license_date            TEXT,   -- 인허가일자
    biz_status_name         TEXT,   -- 영업상태명
    closed_date             TEXT,   -- 폐업일자
    site_area               TEXT,   -- 소재지면적
    site_postal_code        TEXT,   -- 소재지우편번호
    road_postal_code        TEXT,   -- 도로명우편번호
    biz_name                TEXT,   -- 사업장명
    biz_type_name           TEXT,   -- 업태구분명
    data_update_type        TEXT,   -- 데이터갱신구분
    building_ownership      TEXT,   -- 건물소유구분명
    factory_office_staff    TEXT,   -- 공장사무직직원수
    factory_prod_staff      TEXT,   -- 공장생산직직원수
    factory_sales_staff     TEXT,   -- 공장판매직직원수
    water_facility_type     TEXT,   -- 급수시설구분명
    male_staff              TEXT,   -- 남성종사자수
    multi_use_yn            TEXT,   -- 다중이용업소여부
    data_updated_at         TEXT,   -- 데이터갱신시점
    road_address            TEXT,   -- 도로명주소
    grade_type              TEXT,   -- 등급구분명
    deposit                 TEXT,   -- 보증액
    hq_staff                TEXT,   -- 본사직원수
    detail_status_name      TEXT,   -- 상세영업상태명
    detail_status_code      TEXT,   -- 상세영업상태코드
    total_facility_scale    TEXT,   -- 시설총규모
    female_staff            TEXT,   -- 여성종사자수
    biz_status_code         TEXT,   -- 영업상태코드
    surroundings_type       TEXT,   -- 영업장주변구분명
    monthly_rent            TEXT,   -- 월세액
    hygiene_biz_type        TEXT,   -- 위생업태명
    traditional_main_food   TEXT,   -- 전통업소주된음식
    traditional_desig_no    TEXT,   -- 전통업소지정번호
    phone                   TEXT,   -- 전화번호
    coord_x                 TEXT,   -- 좌표정보(X)  EPSG:5174
    coord_y                 TEXT,   -- 좌표정보(Y)  EPSG:5174
    jibun_address           TEXT,   -- 지번주소
    homepage                TEXT,   -- 홈페이지
    last_modified_at        TEXT    -- 최종수정시점
);

COMMENT ON TABLE poi_raw IS
    '원본 그대로 보관 — 규칙을 고쳤을 때 재다운로드 없이 staging부터 다시 만들기 위함. '
    '업무키(관리번호)에 UNIQUE를 걸지 않는다: 원본에 중복이 1건만 있어도 COPY 전체가 중단된다.';

CREATE INDEX ix_poi_raw_run ON poi_raw (run_id);


-- ─────────────────────────────────────────────────────────────────────────────
-- staging — 타입 변환 · 좌표 변환 · 중복 제거 결과. 검수와 회차 diff의 대상.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE poi_staging (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT  NOT NULL REFERENCES ingest_run (id),

    source_id       TEXT    NOT NULL,              -- 관리번호
    name            TEXT,
    category_raw    TEXT,                          -- 업태구분명 원문
    road_address    TEXT,
    jibun_address   TEXT,
    phone           TEXT,

    biz_status_name TEXT,
    licensed_date   DATE,
    closed_date     DATE,

    geom            geometry(Point, 5186),         -- ETL이 5174에서 변환해 넣는다
    coord_x_raw     DOUBLE PRECISION,              -- 원본 5174 좌표. 변환 검증용으로 남긴다
    coord_y_raw     DOUBLE PRECISION,

    is_valid        BOOLEAN NOT NULL DEFAULT TRUE  -- severity=ERROR 검수에 걸리면 false
);

CREATE INDEX ix_poi_staging_run       ON poi_staging (run_id);
CREATE INDEX ix_poi_staging_source_id ON poi_staging (run_id, source_id);
CREATE INDEX ix_poi_staging_geom      ON poi_staging USING GIST (geom);


-- ─────────────────────────────────────────────────────────────────────────────
-- 운영 — 검수 이력
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE validation_result (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT      NOT NULL REFERENCES ingest_run (id),
    rule_code   TEXT        NOT NULL,
    severity    TEXT        NOT NULL CHECK (severity IN ('ERROR', 'WARN')),
    target_id   TEXT,                               -- 대상 source_id
    detail      JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN validation_result.severity IS
    'ERROR는 serving 반영에서 제외하고 ingest_run.rows_rejected 에 집계한다. WARN은 반영하되 이력만 남긴다.';

CREATE INDEX ix_validation_run_rule ON validation_result (run_id, rule_code, severity);
CREATE INDEX ix_validation_target   ON validation_result (target_id);


-- ─────────────────────────────────────────────────────────────────────────────
-- serving — API가 읽는 유일한 테이블
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE category_map (
    category_raw    TEXT PRIMARY KEY,   -- 업태구분명 원문 (표기가 흔들린다)
    category_code   TEXT NOT NULL,
    display_name    TEXT NOT NULL
);

COMMENT ON TABLE category_map IS '인허가의 업태구분명 문자열을 정규화 코드로 바꾼다. /places/search 의 category 파라미터가 이 코드를 받는다';


CREATE TABLE poi (
    id                  BIGSERIAL PRIMARY KEY,

    -- 원본 키를 PK로 쓰지 않는다. 키 체계가 바뀌면 PK와 북마크 FK가 함께 깨진다.
    source              TEXT        NOT NULL,
    source_id           TEXT        NOT NULL,

    name                TEXT        NOT NULL,
    category_code       TEXT,
    category_raw        TEXT,
    road_address        TEXT,
    jibun_address       TEXT,
    phone               TEXT,

    geom                geometry(Point, 5186) NOT NULL,

    status              TEXT        NOT NULL DEFAULT 'ACTIVE'
                                    CHECK (status IN ('ACTIVE', 'CLOSED')),
    licensed_date       DATE,
    closed_date         DATE,

    first_seen_run_id   BIGINT      REFERENCES ingest_run (id),
    last_seen_run_id    BIGINT      REFERENCES ingest_run (id),
    validated_run_id    BIGINT      REFERENCES ingest_run (id),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_poi_source UNIQUE (source, source_id)
);

COMMENT ON TABLE  poi IS 'API가 읽는 유일한 테이블. 갱신은 항상 UPSERT이며 테이블 교체(swap)를 쓰지 않는다';
COMMENT ON COLUMN poi.status IS '폐업은 행 삭제가 아니라 CLOSED 로 남긴다 — 북마크된 POI가 사라지면 방 화면과 계산된 행렬이 깨진다';
COMMENT ON COLUMN poi.last_seen_run_id IS '최신 회차보다 뒤처지면 원본에서 소멸한 것이다';

-- 반경 검색 (/places/nearby). 5186은 미터 단위라 ST_DWithin 에 거리를 그대로 넘긴다.
CREATE INDEX ix_poi_geom     ON poi USING GIST (geom);
-- 부분 일치 검색 (/places/search). LIKE '%q%' 는 B-tree 를 못 타므로 trigram 을 쓴다.
CREATE INDEX ix_poi_name_trgm ON poi USING GIN (name gin_trgm_ops);
CREATE INDEX ix_poi_status   ON poi (status);
CREATE INDEX ix_poi_category ON poi (category_code);
