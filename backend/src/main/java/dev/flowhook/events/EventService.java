package dev.flowhook.events;

import dev.flowhook.endpoints.EndpointRepository;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
    private final EventRepository events;
    private final EndpointRepository endpoints;
    private final JsonMapper json;

    public EventService(EventRepository events, EndpointRepository endpoints, JsonMapper json) {
        this.json = json;
        this.events = events;
        this.endpoints = endpoints;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public EventReceipt accept(EventRequest request) {
        // shortcut: API 키 인증 도입 전 로컬 실습 프로젝트 1로 고정한다.
        long projectId = 1;
        endpoints.lockProject(projectId);
        EventJsonValidation.validate(request);
        String data = json.writeValueAsString(request.data());
        var existing = events.findExisting(projectId, request.eventId(), request.type(), data);
        if (existing.isPresent()) {
            var event = existing.get();
            if (!event.getSameContent()) {
                throw new EventRejected(HttpStatus.CONFLICT, "EVENT_ID_CONFLICT", "같은 eventId에 다른 내용이 접수됐습니다.");
            }
            return new EventReceipt(event.getId(), event.getEventId(), event.getMatchedDeliveryCount(), true);
        }
        if (events.countByProjectId(projectId) >= 10000) {
            throw new EventRejected(HttpStatus.CONFLICT, "RESOURCE_LIMIT", "프로젝트의 이벤트 한도에 도달했습니다.");
        }
        var event = events.save(new EventEntity(projectId, request.eventId(), request.type(), data));
        long id = event.getId();
        int count = events.createDeliveries(id, projectId, request.eventId(), request.type(), data);
        event.setMatchedDeliveryCount(count);
        return new EventReceipt(id, request.eventId(), count, false);
    }
}
