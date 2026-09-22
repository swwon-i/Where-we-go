package com.wherewego.place;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 장소 검색 API (계획서 §4.1). 자체 DB만 조회하고 외부 API 를 부르지 않는다. */
@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {

    private final PlaceRepository repository;

    public PlaceController(PlaceRepository repository) {
        this.repository = repository;
    }

    /** 좌표 기준 반경 검색. ST_DWithin + GiST 성능 측정의 대상 엔드포인트다. */
    @GetMapping("/nearby")
    public List<Place> nearby(
            @RequestParam double lng,
            @RequestParam double lat,
            @RequestParam(defaultValue = "0") int radius,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean includeClosed,
            @RequestParam(defaultValue = "0") int limit) {
        return repository.findNearby(lng, lat, radius, category, includeClosed, limit);
    }

    /** 상호명 부분 일치 검색. 좌표를 주면 거리순 정렬된다. */
    @GetMapping("/search")
    public List<Place> search(
            @RequestParam String q,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double lat,
            @RequestParam(defaultValue = "0") int radius,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean includeClosed,
            @RequestParam(defaultValue = "0") int limit) {
        return repository.search(q, lng, lat, radius, category, includeClosed, limit);
    }

    /**
     * 단건 상세 — 출처(원천·인허가번호), 처음·마지막으로 보인 적재 회차, 검수 기록.
     * 폐업한 곳도 돌려준다. 없으면 404.
     */
    @GetMapping("/{poiId}")
    public ResponseEntity<?> detail(@PathVariable long poiId) {
        return repository.findDetail(poiId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "없는 장소입니다: " + poiId)));
    }
}
