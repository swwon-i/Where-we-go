-- V4 — graph_edge.to_node_id 인덱스
--
-- V3 에서 (build_id, from_node_id) 만 걸고 to_node_id 를 빠뜨렸다.
-- 탐색은 메모리에서 하므로 조회용으로는 없어도 됐지만, **CASCADE 삭제가 이 인덱스를 쓴다.**
--
-- graph_node 를 지우면 PostgreSQL 이 graph_edge 에서 그 노드를 참조하는 행을 찾아야 한다.
-- from_node_id 는 인덱스를 타는데 to_node_id 는 순차 탐색을 한다. 노드 165,050개 ×
-- 엣지 472,866행이면 사실상 끝나지 않는다 — 빌드를 지우려다 10분 넘게 걸려 취소했다.
--
-- 외래키 컬럼에는 인덱스를 걸어야 한다. PostgreSQL 이 자동으로 만들어 주지 않는다.

CREATE INDEX ix_graph_edge_to ON graph_edge (build_id, to_node_id);
