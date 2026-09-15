package com.wherewego.place;

/**
 * 장소 검색 결과 한 건.
 *
 * <p>좌표는 저장(EPSG:5186)이 아니라 <b>응답용 4326</b>이다. 변환은 쿼리에서 한 번만 한다
 * (계획서 §4 공통 규약).
 *
 * @param distanceM 기준 좌표가 주어졌을 때의 거리(미터). 없으면 null.
 */
public record Place(
        long poiId,
        String name,
        String categoryCode,
        String categoryRaw,
        String roadAddress,
        String jibunAddress,
        String phone,
        double lng,
        double lat,
        String status,
        Double distanceM) {}
