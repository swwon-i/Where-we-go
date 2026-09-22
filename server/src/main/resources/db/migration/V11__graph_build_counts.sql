-- V11 — 그래프 빌드 통계를 그래프에서 직접 센 값으로
--
-- graph_build 의 개수 칸이 틀려 있었다.
--
--   nodes_stop      402      ← 지하철만. 버스 정류장 11,042 가 빠졌다
--   nodes_platform  562      ← 지하철만. 버스 플랫폼 36,606 이 빠졌다
--   isolated_nodes  0        ← 세지 않고 0 을 넣고 있었다
--   finished_at     시작 직후 ← now() 는 트랜잭션 시작 시각이다. 빌드 트랜잭션 맨 앞이 찍혔다
--   ALIGHT 엣지     칸 없음
--
-- 개수 칸을 ETL 이 손으로 더하다 생긴 일이다(버스를 넣을 때 노드 쪽을 안 고쳤다). 이제 빌드가
-- 끝날 때 그래프 테이블을 종류 × 수단으로 세어 counts 에 통째로 담고, 합계 칸도 그것으로 채운다.
-- 세는 곳이 하나라 다시 어긋나지 않는다.
--
-- 이전 빌드는 빌드할 때 그래프가 지워지므로 다시 셀 방법이 없다 — 기록이 틀리면 그 빌드의
-- 규모는 영영 모른다. 그래서 그래프가 남아 있는 빌드는 여기서 바로 고쳐 둔다.

ALTER TABLE graph_build
    ADD COLUMN edges_alight INTEGER,
    ADD COLUMN counts JSONB;

COMMENT ON COLUMN graph_build.counts IS
    '노드·엣지를 종류 × 수단으로 센 것. [{"what":"NODE|EDGE","kind":..,"mode":"SUBWAY|BUS|WALK","count":..}]. 엣지의 수단은 도착 노드가 걸린 정류장의 수단이다.';
COMMENT ON COLUMN graph_build.isolated_nodes IS
    '들어오는 엣지도 나가는 엣지도 없는 노드 수. 0 이 아니면 스냅이나 적재가 어딘가 끊긴 것이다.';

-- 그래프가 남아 있는 빌드를 다시 센다. 보통 활성 빌드 하나뿐이다.
WITH n AS (
    SELECT n.build_id, 'NODE' AS what, n.kind, coalesce(s.mode, 'WALK') AS mode, count(*) AS c
    FROM graph_node n LEFT JOIN transit_stop s ON s.id = n.stop_id
    GROUP BY n.build_id, n.kind, s.mode
), e AS (
    SELECT e.build_id, 'EDGE' AS what, e.kind, coalesce(s.mode, 'WALK') AS mode, count(*) AS c
    FROM graph_edge e
    JOIN graph_node t ON t.id = e.to_node_id
    LEFT JOIN transit_stop s ON s.id = t.stop_id
    GROUP BY e.build_id, e.kind, s.mode
), agg AS (
    SELECT build_id,
           jsonb_agg(jsonb_build_object('what', what, 'kind', kind, 'mode', mode, 'count', c)
                     ORDER BY what DESC, kind, mode) AS counts,
           sum(c) FILTER (WHERE what = 'NODE' AND kind = 'WALK')     AS nodes_walk,
           sum(c) FILTER (WHERE what = 'NODE' AND kind = 'STOP')     AS nodes_stop,
           sum(c) FILTER (WHERE what = 'NODE' AND kind = 'PLATFORM') AS nodes_platform,
           sum(c) FILTER (WHERE what = 'EDGE' AND kind = 'WALK')     AS edges_walk,
           sum(c) FILTER (WHERE what = 'EDGE' AND kind = 'ACCESS')   AS edges_access,
           sum(c) FILTER (WHERE what = 'EDGE' AND kind = 'BOARD')    AS edges_board,
           sum(c) FILTER (WHERE what = 'EDGE' AND kind = 'ALIGHT')   AS edges_alight,
           sum(c) FILTER (WHERE what = 'EDGE' AND kind = 'RIDE')     AS edges_ride,
           sum(c) FILTER (WHERE what = 'EDGE' AND kind = 'TRANSFER') AS edges_transfer
    FROM (SELECT * FROM n UNION ALL SELECT * FROM e) x
    GROUP BY build_id
), iso AS (
    SELECT n.build_id, count(*) AS c
    FROM graph_node n
    WHERE NOT EXISTS (SELECT 1 FROM graph_edge e WHERE e.from_node_id = n.id)
      AND NOT EXISTS (SELECT 1 FROM graph_edge e WHERE e.to_node_id = n.id)
    GROUP BY n.build_id
)
UPDATE graph_build b SET
    counts         = a.counts,
    nodes_walk     = a.nodes_walk,
    nodes_stop     = a.nodes_stop,
    nodes_platform = a.nodes_platform,
    edges_walk     = a.edges_walk,
    edges_access   = a.edges_access,
    edges_board    = a.edges_board,
    edges_alight   = a.edges_alight,
    edges_ride     = a.edges_ride,
    edges_transfer = a.edges_transfer,
    isolated_nodes = coalesce((SELECT c FROM iso WHERE iso.build_id = b.id), 0)
FROM agg a
WHERE a.build_id = b.id;
