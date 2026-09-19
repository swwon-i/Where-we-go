package com.wherewego.routing;

import com.wherewego.graph.TransitGraph;
import com.wherewego.graph.TransitMode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 단건 경로.
 *
 * <p>출발·도착은 좌표({@code 127.0276,37.4979})로도 역 이름({@code 강남})으로도 줄 수 있다.
 * 실제 서비스는 좌표를 쓰고, 이름은 결과를 눈으로 확인하기 위한 통로다.
 *
 * <pre>
 *   GET /api/v1/routes?from=강남&amp;to=광화문
 *   GET /api/v1/routes?from=127.0276,37.4979&amp;to=126.9769,37.5759&amp;departureHour=19
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/routes")
public class RouteController {

    private final RouteService service;

    public RouteController(RouteService service) {
        this.service = service;
    }

    /**
     * @param unreachable 닿지 못했을 때 참. 이때 {@code route} 는 null 이다
     */
    public record RouteResponse(
            long buildId,
            Integer departureHour,
            Endpoint from,
            Endpoint to,
            boolean unreachable,
            Integer totalSeconds,
            Integer transfers,
            Double walkDistanceM,
            String summary,
            List<Leg> legs) {}

    /** @param snapDistanceM 좌표로 준 경우 보행망까지의 거리. 멀수록 결과를 덜 믿어야 한다 */
    public record Endpoint(String input, Double snapDistanceM) {}

    @GetMapping
    public RouteResponse route(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) Integer departureHour,
            @RequestParam(defaultValue = "SUBWAY") String mode) {

        var graph = graphOrFail();
        int hour = validateHour(departureHour);
        var transitMode = parseMode(mode);

        var origin = resolve(graph, from, transitMode);
        var target = resolve(graph, to, transitMode);

        var route = service.route(graph, origin.node(), target.node(), hour);
        var fromDto = new Endpoint(from, nullIfZero(origin.snapDistanceM()));
        var toDto = new Endpoint(to, nullIfZero(target.snapDistanceM()));

        if (route == null) {
            return new RouteResponse(
                    graph.buildId(), departureHour, fromDto, toDto,
                    true, null, null, null, null, List.of());
        }
        return new RouteResponse(
                graph.buildId(),
                departureHour,
                fromDto,
                toDto,
                false,
                route.totalSeconds(),
                route.transfers(),
                route.walkDistanceM(),
                route.summary(),
                route.legs());
    }

    /** 좌표처럼 생겼으면 좌표로, 아니면 이름으로 본다. */
    private RouteService.Endpoint resolve(TransitGraph graph, String value, TransitMode mode) {
        var coord = parseCoordinate(value);
        try {
            return coord == null
                    ? service.atStop(graph, mode, value)
                    : service.atCoordinate(graph, coord[0], coord[1]);
        } catch (RouteService.NoSuchNodeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RouteService.StaleGraphException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** {@code "lng,lat"} 이면 좌표 두 개, 아니면 null. */
    static double[] parseCoordinate(String value) {
        int comma = value.indexOf(',');
        if (comma < 0) return null;
        try {
            double lng = Double.parseDouble(value.substring(0, comma).trim());
            double lat = Double.parseDouble(value.substring(comma + 1).trim());
            return new double[] {lng, lat};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int validateHour(Integer hour) {
        if (hour == null) return Dijkstra.NO_HOUR;
        if (hour < 0 || hour > 23) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "departureHour 는 0~23 이다: " + hour);
        }
        return hour;
    }

    private static TransitMode parseMode(String mode) {
        try {
            return TransitMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode 는 SUBWAY 나 BUS 다");
        }
    }

    private TransitGraph graphOrFail() {
        try {
            return service.graph();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    private static Double nullIfZero(double v) {
        return v <= 0 ? null : Math.round(v * 10) / 10.0;
    }
}
