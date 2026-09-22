package com.wherewego.place;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 장소 한 건의 상세 (계획서 §4 {@code GET /places/{poiId}}).
 *
 * <p>검색 결과({@link Place})에 더해 <b>이 레코드가 어디서 왔고 파이프라인이 무엇을 봤는지</b>를
 * 싣는다. 원천과 인허가번호, 처음·마지막으로 보인 적재 회차, 검수에 걸린 기록.
 *
 * <p><b>영업상태 변경 이력은 없다.</b> 계획서 명세에는 있지만 {@code poi} 는 현재 상태와 폐업일만
 * 들고 있고, 과거 상태를 쌓는 테이블이 없다. 지어내지 않는다 — 회차마다 상태를 남기는 이력
 * 테이블을 두어야 성립한다.
 *
 * @param place 검색 결과와 같은 기본 정보
 * @param source 원천. {@code LOCALDATA_FOOD}(일반음식점) / {@code LOCALDATA_REST}(휴게음식점)
 * @param sourceId 원천의 식별자 — 인허가번호. 원천이 같아야 같은 레코드다
 * @param firstSeen 이 레코드가 처음 들어온 적재 회차
 * @param lastSeen 가장 최근에 파일에서 확인된 적재 회차. 오래됐다면 원본에서 사라진 것이다
 * @param validations 이 레코드가 걸린 검수 기록. 최근 회차부터
 */
public record PlaceDetail(
        Place place,
        String source,
        String sourceId,
        LocalDate licensedDate,
        LocalDate closedDate,
        Run firstSeen,
        Run lastSeen,
        List<Validation> validations) {

    /** 적재 회차 하나. {@code snapshotDate} 는 원본 파일의 기준일이다. */
    public record Run(long runId, LocalDate snapshotDate, OffsetDateTime startedAt) {}

    /**
     * @param detail 규칙마다 모양이 다른 판정 근거. 예: 중복이면 같은 상호·주소 묶음 크기
     * @param checkedAt 검수한 시각
     */
    public record Validation(
            long runId, String ruleCode, String severity, JsonNode detail, OffsetDateTime checkedAt) {}
}
