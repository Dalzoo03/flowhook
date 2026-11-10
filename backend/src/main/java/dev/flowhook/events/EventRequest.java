package dev.flowhook.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

record EventRequest(String eventId, String type, String data) {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNumberLength(65536).maxNameLength(65536).maxStringLength(65536).build())
            .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    static EventRequest parse(byte[] bytes) {
        try {
            Object value = JSON.readValue(bytes, Object.class);
            if (!(value instanceof Map<?, ?> body) || !body.keySet().equals(Set.of("eventId", "type", "data"))
                    || !(body.get("eventId") instanceof String id) || !identifier(id)
                    || !(body.get("type") instanceof String type) || !identifier(type)
                    || !(body.get("data") instanceof Map<?, ?> data)) {
                throw EventRejected.invalid();
            }
            validateJsonbValue(body);
            return new EventRequest(id, type, JSON.writeValueAsString(data));
        } catch (JacksonException exception) {
            throw EventRejected.invalid();
        }
    }

    private static boolean identifier(String value) {
        return !value.isBlank() && value.codePointCount(0, value.length()) <= 128;
    }

    private static void validateJsonbValue(Object value) {
        if (value instanceof Map<?, ?> object) {
            object.forEach((key, item) -> { validateJsonbValue(key); validateJsonbValue(item); });
        } else if (value instanceof List<?> array) {
            array.forEach(EventRequest::validateJsonbValue);
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
