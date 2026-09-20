package com.wherewego.room;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 방 화면이 한 번에 받는 것. 프론트는 이 엔드포인트만 폴링한다.
 *
 * @param inviteCode 사람이 받아 적어 들어올 수 있는 코드. 참가자에게만 보인다 —
 *     방 안의 정보이므로 밖에서 조회할 수 있으면 코드의 뜻이 없어진다
 * @param matrixReady <b>계산 가능 여부</b>다. 계산 완료 여부가 아니다 — 행렬은 동기 계산이라
 *     "진행 중" 이라는 상태가 없다. 출발지를 정한 사람이 하나라도 있고 후보가 하나라도 있으면 참
 */
public record RoomView(
        UUID roomId,
        String title,
        String ownerNickname,
        boolean iAmOwner,
        String inviteCode,
        Instant createdAt,
        List<MemberView> members,
        List<BookmarkView> bookmarks,
        boolean matrixReady) {

    /**
     * 참가자 한 명.
     *
     * <p>계획서는 타인의 식별자를 감추라고 했지만, 그것은 식별자 <b>소지가 곧 권한</b>이던
     * 익명 구조에서의 제약이다. 지금은 세션이 본인을 확인하므로 id 를 알아도 아무것도 못 한다.
     * 오히려 행렬의 행을 가리키려면 id 가 필요하다.
     *
     * @param originSnapM 출발지가 보행망에서 떨어진 거리. 크면 결과를 덜 믿어야 한다
     * @param originStale 스냅한 그래프가 지금 쓰는 그래프와 다르다. 계산 전에 다시 붙인다
     */
    public record MemberView(
            long memberId,
            String nickname,
            boolean isMe,
            boolean originSet,
            String originLabel,
            Double originLng,
            Double originLat,
            Double originSnapM,
            boolean originStale) {}

    /**
     * 후보 장소 한 곳.
     *
     * @param poiId 우리 POI 에서 고른 경우의 원본. 지도를 찍어 넣었으면 null
     * @param name 추가 시점의 이름. 원본이 바뀌어도 따라가지 않는다
     */
    public record BookmarkView(
            long bookmarkId,
            Long poiId,
            String name,
            String address,
            double lng,
            double lat,
            String addedByNickname,
            boolean addedByMe,
            Instant createdAt) {}
}
