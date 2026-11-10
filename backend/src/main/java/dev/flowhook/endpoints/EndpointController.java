package dev.flowhook.endpoints;

import java.net.URI;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "전달 대상")
@RequestMapping("/api/projects/{projectId}/endpoints")
public class EndpointController {
    private final EndpointService service;

    public EndpointController(EndpointService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "전달 대상 등록")
    @ApiResponse(responseCode = "201", description = "등록 완료")
    public ResponseEntity<Endpoint> create(@Parameter(description = "현재 실습 프로젝트는 1", example = "1") @PathVariable long projectId, @RequestBody CreateEndpointRequest request) {
        Endpoint endpoint = service.create(projectId, request.name(), request.url(), request.eventType());
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/endpoints/" + endpoint.id()))
                .body(endpoint);
    }

    @GetMapping
    @Operation(summary = "전달 대상 목록 조회")
    public List<Endpoint> findAll(@Parameter(description = "현재 실습 프로젝트는 1", example = "1") @PathVariable long projectId) {
        return service.findAll(projectId);
    }

    public record CreateEndpointRequest(
            @Schema(example = "배송 서비스") String name,
            @Schema(example = "https://partner.example.com/webhooks") String url,
            @Schema(example = "order.created") String eventType) {
    }
}
