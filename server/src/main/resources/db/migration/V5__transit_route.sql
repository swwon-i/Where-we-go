-- V5 — 노선 이름표
--
-- graph_edge.line 은 **식별자**다. 지하철은 '2' 라서 그대로 읽히지만 버스는
-- '100100017' 이라 경로 결과가 "100100017 을 타고 11정차" 로 나온다.
--
-- line 을 노선 명칭으로 바꾸는 방법도 있지만 그러면 안 된다. line 은 플랫폼 노드를
-- (정류장 × 노선) 으로 가르는 키라서, 이름이 겹치는 두 노선이 하나로 합쳐지면
-- 그래프 자체가 틀린다. 식별자는 그대로 두고 표시용 이름을 옆에 둔다.
--
-- 조인 키는 (mode, source_id) = (노드의 mode, graph_edge.line) 이다.

CREATE TABLE transit_route (
    id          BIGSERIAL PRIMARY KEY,
    mode        TEXT NOT NULL CHECK (mode IN ('SUBWAY', 'BUS')),
    source_id   TEXT NOT NULL,      -- graph_edge.line 과 같은 값
    name        TEXT NOT NULL,      -- 사람이 읽는 이름 ('146', '강남01', '2호선')
    route_type  TEXT,               -- 버스 노선_유형 (간선/지선/마을/순환)
    CONSTRAINT uq_transit_route UNIQUE (mode, source_id)
);

COMMENT ON TABLE transit_route IS '노선 식별자 → 표시 이름. 탐색에는 쓰이지 않는다';
