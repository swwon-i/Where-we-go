-- V10 — 시간대 가중치의 "운행 없음"
--
-- 지금까지 weight_by_hour 는 24칸이 모두 초 단위 비용이었다. 운행하지 않는 시간대가 없다는
-- 뜻인데 실제로는 있다. ETL 은 그 칸을 구간 중앙값으로 채웠고, 그 결과 새벽 0~4시에만 다니는
-- N26 이 낮 19시간 동안 다니는 노선이 됐고, 새벽 4시에 지하철이 다녔다.
--
-- 이제 운행하지 않는 칸에는 -1 을 쓴다. 탐색(Dijkstra.java, route.py)은 음수 칸의 엣지를
-- 건너뛴다. 0 을 쓰지 않는 이유는 0초가 "공짜로 지나간다"로 읽히기 때문이다 — 그래서 0 은
-- 아예 허용하지 않는다.
--
-- 이 제약은 새 값을 받아들이기 위한 것이 아니라(이전에도 막는 제약이 없었다) 규칙을 스키마에
-- 적어 두기 위한 것이다. 이전 빌드의 값은 모두 1 이상이라 그대로 통과한다.

ALTER TABLE graph_edge
    ADD CONSTRAINT ck_graph_edge_hour_values
        CHECK (weight_by_hour IS NULL
               OR (-1 <= ALL (weight_by_hour) AND NOT (0 = ANY (weight_by_hour))));

COMMENT ON COLUMN graph_edge.weight_by_hour IS
    '시간대별 가중치 24개(초). departureHour 로 인덱싱한다. -1 은 그 시간대에 운행하지 않는다는 뜻이며 탐색이 건너뛴다.';
