package dev.flowhook.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서버가 HTTP 요청에 응답하는지 확인하는 첫 번째 API. */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("UP");
    }

    // 반환 객체는 Spring이 JSON으로 변환한다.
    public record HealthResponse(String status) {
    }
}
