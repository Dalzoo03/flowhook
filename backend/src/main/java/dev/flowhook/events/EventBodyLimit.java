package dev.flowhook.events;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice(assignableTypes = EventController.class)
public class EventBodyLimit extends RequestBodyAdviceAdapter {
    private static final int MAX_BYTES = 65536;

    @Override
    public boolean supports(MethodParameter parameter, Type target, Class<? extends HttpMessageConverter<?>> converter) {
        return target == EventRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type target,
                                          Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        if (message.getHeaders().getContentLength() > MAX_BYTES) throw tooLarge();
        byte[] bytes = message.getBody().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw tooLarge();
        return new HttpInputMessage() {
            public HttpHeaders getHeaders() { return message.getHeaders(); }
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
        };
    }

    private EventRejected tooLarge() {
        return new EventRejected(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "이벤트 본문은 최대 64 KiB입니다.");
    }
}
