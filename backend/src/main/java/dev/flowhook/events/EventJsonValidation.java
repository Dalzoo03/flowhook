package dev.flowhook.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

final class EventJsonValidation {
    static void validate(EventRequest request) {
        if (request.eventId().codePointCount(0, request.eventId().length()) > 128
                || request.type().codePointCount(0, request.type().length()) > 128) throw EventRejected.invalid();
        validateJsonbValue(request.eventId());
        validateJsonbValue(request.type());
        validateJsonbValue(request.data());
    }

    private static void validateJsonbValue(Object value) {
        if (value instanceof Map<?, ?> object) {
            object.forEach((key, item) -> { validateJsonbValue(key); validateJsonbValue(item); });
        } else if (value instanceof List<?> array) {
            array.forEach(EventJsonValidation::validateJsonbValue);
        } else if (value instanceof String text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == 0 || Character.isLowSurrogate(c)) throw EventRejected.invalid();
                if (Character.isHighSurrogate(c)) {
                    if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw EventRejected.invalid();
                }
            }
        } else if (value instanceof BigDecimal number) {
            // PostgreSQL numeric/jsonb의 정수·소수 자릿수 한도를 입력 단계에서 검사한다.
            if ((long) number.precision() - number.scale() > 131072 || number.scale() > 16383) {
                throw EventRejected.invalid();
            }
        }
    }
}
