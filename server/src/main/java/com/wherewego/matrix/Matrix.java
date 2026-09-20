package com.wherewego.matrix;

import java.util.List;

/**
 * 이동시간 행렬 — 이 서비스의 핵심 응답.
 *
 * <h2>순위를 매기지 않는다</h2>
 *
 * "여기가 제일 낫다"고 말하지 않는다. 각 칸의 소요시간·환승·도보거리를 있는 그대로 내보내고
 * 판단은 사용자에게 맡긴다. 누구의 시간을 얼마나 중하게 볼지는 그 모임이 정할 일이지
 * 우리가 목적함수로 정할 일이 아니다(계획서 §1).
 *
 * <h2>왜 행이 장소인가</h2>
 *
 * 한 줄을 훑으면 <b>그 장소가 모두에게 어떤지</b>가 보인다. 사용자가 고르는 것은 장소이므로
 * 비교 단위도 장소여야 한다. 사람이 행이면 한 사람의 선택지를 훑게 되어 질문이 뒤집힌다.
 *
 * @param rows 장소마다 한 줄. 각 줄의 {@code cells} 는 {@code origins} 순서와 같다
 */
public record Matrix(
        long graphBuildId,
        Integer departureHour,
        List<Origin> origins,
        List<Row> rows,
        Stats stats) {

    /**
     * 열 — 출발지 하나.
     *
     * @param snapDistanceM 보행망까지의 거리. 크면 이 열 전체를 덜 믿어야 한다
     * @param resnapped 그래프가 바뀌어 이번에 다시 붙였는가
     */
    public record Origin(
            long memberId,
            String nickname,
            String label,
            double snapDistanceM,
            boolean resnapped) {}

    /**
     * 행 — 후보 장소 하나.
     *
     * @param snapDistanceM 후보가 보행망에서 떨어진 거리. 붙이지 못했으면 null
     */
    public record Row(
            long bookmarkId,
            String name,
            String address,
            double lng,
            double lat,
            Double snapDistanceM,
            List<Cell> cells) {}

    /**
     * 칸 하나.
     *
     * <p>닿지 못한 칸은 <b>그 칸만</b> 실패다. 후보 하나에 못 가더라도 나머지 행렬은 쓸모가 있다.
     *
     * @param reason 닿지 못한 이유. {@code NO_WALK_NETWORK}(붙일 보행망이 없음) /
     *     {@code UNREACHABLE}(그래프상 경로가 없음)
     */
    public record Cell(
            boolean reachable,
            Integer durationSeconds,
            Integer transfers,
            Double walkDistanceM,
            String summary,
            String reason) {

        static Cell unreachable(String reason) {
            return new Cell(false, null, null, null, null, reason);
        }
    }

    /**
     * 성능 서사를 담는 자리.
     *
     * @param dijkstraRuns <b>출발지 수와 같고 후보 수와 무관하다.</b> one-to-many 탐색의 증거다 —
     *     후보를 늘려도 이 값이 안 늘면 한 번 퍼뜨려 전부 거둬들이고 있다는 뜻이다
     * @param expandedNodes 확정한 노드 수 합계. 조기 종료가 먹는지 보는 값이다
     * @param graphSource 그래프를 메모리에서 썼는지({@code CACHE}) 이번에 읽었는지({@code DB})
     */
    public record Stats(
            int dijkstraRuns,
            int expandedNodes,
            long elapsedMs,
            String graphSource,
            long graphBuildId,
            int originCount,
            int destinationCount) {}
}
