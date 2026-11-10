package dev.flowhook.events;

import java.io.IOException;
import java.util.UUID;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "이벤트", description = "로컬 실습 프로젝트 1의 이벤트 접수. 실제 외부 전송은 아직 수행하지 않습니다.")
public class EventController {
    private static final int MAX_BODY_BYTES = 65536;
    private static final Logger LOG = LoggerFactory.getLogger(EventController.class);
    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @PostMapping(value = "/v1/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "이벤트 접수", description = "최대 64 KiB. 동일 eventId·동일 내용은 기존 결과를 반환하고, 다른 내용은 충돌합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = EventInput.class),
                            examples = @ExampleObject(value = "{\"eventId\":\"order-1001\",\"type\":\"order.created\",\"data\":{\"orderId\":1001}}"))))
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "신규 이벤트와 전달 대기 저장", content = @Content(schema = @Schema(implementation = EventReceipt.class))),
            @ApiResponse(responseCode = "200", description = "동일 이벤트 재접수", content = @Content(schema = @Schema(implementation = EventReceipt.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 입력", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "EVENT_ID_CONFLICT 또는 RESOURCE_LIMIT", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "64 KiB 초과", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "저장 실패", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<EventReceipt> accept(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw tooLarge();
        EventReceipt receipt = service.accept(EventRequest.parse(body));
        return ResponseEntity.status(receipt.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(receipt);
    }

    @ExceptionHandler(EventRejected.class)
    ResponseEntity<ApiError> rejected(EventRejected exception) {
        return ResponseEntity.status(exception.status)
                .body(new ApiError(exception.code, exception.getMessage(), UUID.randomUUID().toString()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> storageFailure(RuntimeException exception) {
        String traceId = UUID.randomUUID().toString();
        LOG.warn("Event storage failed traceId={}", traceId);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("STORAGE_UNAVAILABLE", "이벤트를 저장하지 못했습니다. 잠시 후 재시도하세요.", traceId));
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ApiError> unreadableBody(IOException exception) {
        return rejected(EventRejected.invalid());
    }

    private EventRejected tooLarge() {
        return new EventRejected(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "이벤트 본문은 최대 64 KiB입니다.");
    }

    record ApiError(String code, String message, String traceId) {
    }

    // 문서용 모델이다. 실제 요청은 기존의 크기 제한·엄격한 JSON 파싱 경로로 처리한다.
    @Schema(name = "EventInput", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record EventInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 128) String eventId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 128) String type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE) Map<String, Object> data) {
    }
}
