package com.wherewego.graph;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 올려둔 그래프의 상태.
 *
 * <p>계획서의 {@code /admin/graph-stats} 자리다. 빌드 번호와 적재 시각을 볼 수 있어야
 * "경로가 이상한데 옛 그래프를 보고 있는 것 아닌가"를 바로 판별할 수 있다.
 */
@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {

    private final GraphCache cache;

    public GraphController(GraphCache cache) {
        this.cache = cache;
    }

    @GetMapping
    public GraphCache.Status status() {
        return cache.status();
    }

    /** 활성 빌드를 다시 읽는다. ETL 이 그래프를 새로 만든 뒤 한 번 부르면 된다. */
    @PostMapping("/reload")
    public GraphCache.Status reload() {
        try {
            cache.reload();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
        return cache.status();
    }
}
