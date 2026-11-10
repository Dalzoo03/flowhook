package dev.flowhook.events;

import java.io.IOException;
import java.util.UUID;
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
public class EventController {
    private static final int MAX_BODY_BYTES = 65536;
    private static final Logger LOG = LoggerFactory.getLogger(EventController.class);
    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @PostMapping(value = "/v1/events", consumes = MediaType.APPLICATION_JSON_VALUE)
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
}
