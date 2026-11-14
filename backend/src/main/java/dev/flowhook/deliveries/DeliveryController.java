package dev.flowhook.deliveries;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/projects/{projectId}/deliveries")
@ConditionalOnProperty(name = "flowhook.delivery.local-receiver-enabled", havingValue = "true")
@Tag(name = "전송 실습(로컬)", description = "local 프로필의 데모 수신기로 한 번 전송합니다. 자동 재시도는 없습니다.")
public class DeliveryController {
    private final DeliveryService service;
    private final DeliveryTransactions transactions;

    public DeliveryController(DeliveryService service, DeliveryTransactions transactions) {
        this.service = service;
        this.transactions = transactions;
    }

    @GetMapping
    @Operation(summary = "최근 전달 작업 최대 100건 조회")
    public List<DeliveryTransactions.DeliveryView> list(@Parameter(example = "1") @PathVariable long projectId) {
        if (projectId != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return transactions.list(projectId);
    }

    @PostMapping("/{deliveryId}/send")
    @Operation(summary = "선택한 PENDING 작업을 한 번 전송", description = "HTTP 200은 시도 처리 완료입니다. 응답의 status·outcome·httpStatus로 전송 결과를 확인하세요. 실패는 이번 실습에서 DEAD로 기록합니다.")
    public DeliveryTransactions.DeliveryResult send(@Parameter(example = "1") @PathVariable long projectId,
                                                   @Parameter(example = "1") @PathVariable long deliveryId) {
        return service.sendOnce(projectId, deliveryId);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Map<String, String>> storageFailure(RuntimeException exception) {
        String traceId = UUID.randomUUID().toString();
        LoggerFactory.getLogger(DeliveryController.class).warn("Delivery storage failed traceId={}", traceId);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "STORAGE_UNAVAILABLE",
                "message", "전송 상태를 저장하지 못했습니다. DB 이력을 확인하세요. 자동 재전송하지 않습니다.", "traceId", traceId));
    }
}
