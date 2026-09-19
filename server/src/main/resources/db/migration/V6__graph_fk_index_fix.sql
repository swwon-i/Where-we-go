-- V6 — V4 의 인덱스가 틀렸다
--
-- V4 에서 CASCADE 삭제를 빠르게 하려고 (build_id, to_node_id) 인덱스를 만들었다.
-- **그 인덱스는 쓰이지 않는다.**
--
-- 외래키는 to_node_id 하나에만 걸려 있다. graph_node 의 행이 지워지면 PostgreSQL 이
-- 내부적으로 도는 질의는
--
--     DELETE FROM graph_edge WHERE to_node_id = $1
--
-- 이고, 여기에는 build_id 조건이 없다. B-tree 는 선두 컬럼부터 훑으므로
-- (build_id, to_node_id) 로는 이 질의를 탈 수 없다. 결국 1,821,462행을 순차 탐색하며,
-- 그것을 지우는 노드 수만큼 반복한다 — 빌드 하나 정리하는 데 17분이 지나도 안 끝났다.
--
-- V4 를 만들 때 인덱스를 걸었다는 사실만 확인하고 **실제로 그 인덱스를 타는지는
-- 확인하지 않았다.** 전체 삭제를 TRUNCATE 로 바꾸면서 이 경로가 한동안 안 쓰여
-- 드러나지 않았고, prune_old_builds 가 다시 밟으면서 나왔다.
--
-- 복합 인덱스는 build_id 로 거르는 조회에 여전히 쓸모가 있으므로 남겨 둔다.

CREATE INDEX ix_graph_edge_to_node ON graph_edge (to_node_id);
CREATE INDEX ix_graph_edge_from_node ON graph_edge (from_node_id);
