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
@Tag(name = "전송 실습(로컬)", description = "local worker가 자동 전송합니다. 수동 실행 API도 유지하며 worker와 경쟁하면 409가 반환될 수 있습니다.")
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
    @Operation(summary = "PENDING 또는 예약 시간이 지난 RETRY_WAIT 작업 1회 실행", description = "HTTP 200은 시도 처리 완료입니다. 재시도 가능한 실패는 RETRY_WAIT와 nextAttemptAt으로 예약하며 최초 포함 최대 5회입니다. local 프로필의 worker가 예약 후 자동 실행합니다. 수동 실습은 worker-enabled=false로 끄고 같은 전달 ID를 실행하세요. 시간 전 실행·종료 상태는 409입니다.")
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
