package dev.flowhook.events;

import org.springframework.http.HttpStatus;

final class EventRejected extends RuntimeException {
    final HttpStatus status;
    final String code;

    EventRejected(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static EventRejected invalid() {
        return new EventRejected(HttpStatus.BAD_REQUEST, "INVALID_EVENT", "eventId·type은 1~128자 문자열, data는 JSON 객체여야 합니다. 올바른 JSON과 정의된 필드만 사용하세요.");
    }
}
