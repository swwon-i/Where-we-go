-- V3 — 경로탐색 그래프 · 역/정류장 원본 · 역명 매핑
--
-- 모델 (계획서 §2 "수단 추가에 열린 그래프")
-- ------------------------------------------------------------------
--   도보·지하철·버스를 각각 다른 시스템으로 두지 않는다. 전부 노드와 엣지로 바꿔
--   하나의 그래프에 넣고 다익스트라를 한 번 돌린다. 수단별 분기 로직이 없고,
--   모든 규칙이 엣지 가중치(초)로만 표현된다.
--
--   [보행]  OSM 교차점
--      │ ACCESS   진출입 도보
--   [정류장]  서울역                 ← 역·정류장 자체. 드나드는 문
--      │ BOARD    대기 = 배차 ÷ 2
--   [플랫폼]  서울역·1호선           ← 노선별로 쪼갠 노드
--      │ RIDE     역간 소요시간
--   [플랫폼]  시청역·1호선
--
--   노드를 두 겹으로 나눈 이유는 **대기시간을 붙일 자리** 때문이다.
--   역 노드가 하나뿐이면 "새로 타는 것"과 "계속 타고 가는 것"을 구분할 수 없어
--   연속 승차에도 대기가 붙는다. 정류장 → 플랫폼 진입(BOARD)에만 대기를 두면
--   올라타는 순간에만 비용이 생긴다.
--
--   환승은 플랫폼끼리 직접 잇는다(TRANSFER). 정류장 노드를 경유시키면
--   최초 승차와 환승을 구분하지 못해 환승 도보시간을 붙일 자리가 없어진다.
--
-- DB 의 역할
-- ------------------------------------------------------------------
--   탐색은 여기서 하지 않는다. 서버가 기동할 때 그래프를 통째로 메모리에 올리고
--   다익스트라는 거기서 돈다. 이 테이블들은 보관·확인·통계를 위한 것이다.

-- ─────────────────────────────────────────────────────────────────────────────
-- 운영 — 빌드 회차
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE graph_build (
    id                  BIGSERIAL PRIMARY KEY,
    status              TEXT        NOT NULL DEFAULT 'RUNNING'
                                    CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    -- 활성 빌드는 한 번에 하나뿐이다. 재빌드 중인 반쪽 그래프를 서버가 읽지 않도록,
    -- 빌드가 끝난 뒤에만 이 플래그를 옮긴다.
    is_active           BOOLEAN     NOT NULL DEFAULT FALSE,

    area                TEXT,                   -- 대상 구역 (예: 'Seoul, South Korea')
    walk_speed_mps      REAL,                   -- 도보 속도. 재현을 위해 남긴다

    nodes_walk          INTEGER,
    nodes_stop          INTEGER,
    nodes_platform      INTEGER,
    edges_walk          INTEGER,
    edges_access        INTEGER,
    edges_board         INTEGER,
    edges_ride          INTEGER,
    edges_transfer      INTEGER,

    -- 강·철도로 끊긴 조각. 서울 전체 보행망은 연결요소가 1개라 0 이 나오지만,
    -- 구역을 좁히면 생길 수 있어 기록해 둔다.
    isolated_nodes      INTEGER,
    -- 환승 실측값이 없어 기본값으로 때운 비율. /admin/graph-stats 에 노출한다.
    transfer_fallback_ratio REAL,

    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    duration_ms         BIGINT,
    error_message       TEXT
);

COMMENT ON TABLE  graph_build IS '그래프 빌드 회차. /admin/graph-stats 의 원천';
COMMENT ON COLUMN graph_build.is_active IS
    '서버가 읽어갈 빌드. 빌드 도중에는 FALSE 로 두고 완료 후 옮긴다 — 반쪽 그래프를 읽으면 경로가 조용히 끊긴다';

-- 활성 빌드가 둘이면 어느 쪽을 읽을지 알 수 없다. 하나만 허용한다.
CREATE UNIQUE INDEX uq_graph_build_active ON graph_build (is_active) WHERE is_active;


-- ─────────────────────────────────────────────────────────────────────────────
-- serving — 역·정류장 원본
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE transit_stop (
    id              BIGSERIAL PRIMARY KEY,
    mode            TEXT NOT NULL CHECK (mode IN ('SUBWAY', 'BUS')),
    -- 원본 식별자. 지하철은 시각표의 SI_ID, 버스는 정류장_ID.
    source_id       TEXT NOT NULL,
    name            TEXT NOT NULL,
    -- 원본은 위경도(4326)지만 저장은 5186 으로 통일한다 (계획서 §3).
    geom            geometry(Point, 5186) NOT NULL,

    CONSTRAINT uq_transit_stop UNIQUE (mode, source_id)
);

COMMENT ON TABLE transit_stop IS
    '역·정류장 자체. 노선 정보는 붙이지 않는다 — 한 역에 여러 노선이 서므로 노선별 구분은 graph_node(PLATFORM) 가 맡는다';

CREATE INDEX ix_transit_stop_geom ON transit_stop USING GIST (geom);
CREATE INDEX ix_transit_stop_name ON transit_stop (name);


CREATE TABLE station_alias (
    id                  BIGSERIAL PRIMARY KEY,
    line                TEXT NOT NULL,      -- 시각표 LINE ('1'~'9')
    timetable_name      TEXT NOT NULL,      -- 시각표 STATION_NM
    master_name         TEXT NOT NULL,      -- 역사마스터 역사명
    note                TEXT,

    CONSTRAINT uq_station_alias UNIQUE (line, timetable_name)
);

COMMENT ON TABLE station_alias IS
    '시각표와 역사마스터의 역명이 어긋나는 경우를 맨다. 역사마스터가 1호선 바깥을 '
    '경부선·경인선·경원선으로, 4호선을 안산선·과천선으로 담는 탓에 매핑 없이는 70.6%%만 붙는다. '
    '개명(당고개→불암산)과 표기 차이(신내역→신내)도 여기서 처리한다.';


-- ─────────────────────────────────────────────────────────────────────────────
-- 그래프 — 노드
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE graph_node (
    id              BIGSERIAL PRIMARY KEY,
    build_id        BIGINT NOT NULL REFERENCES graph_build (id) ON DELETE CASCADE,

    --   WALK      OSM 교차점
    --   STOP      역·정류장 자체. 진출입의 문
    --   PLATFORM  (정류장 × 노선). 실제로 타는 자리
    kind            TEXT NOT NULL CHECK (kind IN ('WALK', 'STOP', 'PLATFORM')),

    -- 원본 식별자. WALK 는 OSM node id, STOP/PLATFORM 은 transit_stop.source_id.
    source_id       TEXT,
    -- PLATFORM 만 채운다. 같은 역이라도 노선이 다르면 다른 노드다.
    line            TEXT,
    stop_id         BIGINT REFERENCES transit_stop (id),

    geom            geometry(Point, 5186) NOT NULL
);

COMMENT ON COLUMN graph_node.kind IS
    'STOP 과 PLATFORM 을 나눈 것은 대기시간을 붙일 자리를 만들기 위해서다. '
    'STOP→PLATFORM(BOARD) 에만 대기를 두면 연속 승차에는 붙지 않는다.';

CREATE INDEX ix_graph_node_build      ON graph_node (build_id);
CREATE INDEX ix_graph_node_geom       ON graph_node USING GIST (geom);
-- 좌표 스냅은 활성 빌드의 WALK/STOP 노드만 훑는다.
CREATE INDEX ix_graph_node_build_kind ON graph_node (build_id, kind);
-- 빌드 중 "이 원본 id 의 노드가 이미 있나"를 반복 조회한다.
CREATE UNIQUE INDEX uq_graph_node_src
    ON graph_node (build_id, kind, source_id, COALESCE(line, ''));


-- ─────────────────────────────────────────────────────────────────────────────
-- 그래프 — 엣지
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE graph_edge (
    id              BIGSERIAL PRIMARY KEY,
    build_id        BIGINT NOT NULL REFERENCES graph_build (id) ON DELETE CASCADE,

    from_node_id    BIGINT NOT NULL REFERENCES graph_node (id) ON DELETE CASCADE,
    to_node_id      BIGINT NOT NULL REFERENCES graph_node (id) ON DELETE CASCADE,

    --   WALK      보행 ↔ 보행        거리 ÷ 도보속도
    --   ACCESS    보행 ↔ 정류장       진출입 도보
    --   BOARD     정류장 → 플랫폼     대기 = 배차 ÷ 2
    --   RIDE      플랫폼 → 플랫폼     역간 소요시간
    --   TRANSFER  플랫폼 ↔ 플랫폼     환승 도보 + 대기
    kind            TEXT NOT NULL
                    CHECK (kind IN ('WALK', 'ACCESS', 'BOARD', 'RIDE', 'TRANSFER')),

    -- 기본 가중치(초). 시간대와 무관한 엣지는 이 값만 쓴다.
    weight_sec      INTEGER NOT NULL CHECK (weight_sec >= 0),

    -- 시간대별 가중치(초). 0시부터 23시까지 24개.
    -- 버스 구간 운행시간과 지하철·버스 배차가 시간대별이라 필요하다.
    -- 별도 테이블로 빼면 행이 100만을 넘는데, 그래프를 통째로 메모리에 올리므로
    -- 조인할 이유가 없다. NULL 이면 weight_sec 을 그대로 쓴다.
    weight_by_hour  SMALLINT[],

    -- 표시용. 경로 상세에서 "2호선", "146번" 을 보여줘야 한다.
    line            TEXT,
    -- WALK/ACCESS 의 실제 거리(m). 응답의 walkDistance 와 스냅 신뢰도 판단에 쓴다.
    distance_m      REAL,

    CONSTRAINT ck_graph_edge_hours
        CHECK (weight_by_hour IS NULL OR array_length(weight_by_hour, 1) = 24)
);

COMMENT ON COLUMN graph_edge.weight_by_hour IS
    '시간대별 가중치 24개. departureHour 로 인덱싱한다. 그래프는 한 번만 빌드하고 탐색 때 고른다.';

-- 다익스트라가 메모리에서 도므로 이 인덱스는 탐색용이 아니라
-- 빌드·적재·확인용이다. 그래프를 메모리로 올릴 때 build_id 로 한 번에 읽는다.
CREATE INDEX ix_graph_edge_build ON graph_edge (build_id);
CREATE INDEX ix_graph_edge_from  ON graph_edge (build_id, from_node_id);
CREATE INDEX ix_graph_edge_kind  ON graph_edge (build_id, kind);
