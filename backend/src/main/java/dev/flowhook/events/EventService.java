package dev.flowhook.events;

import dev.flowhook.endpoints.JdbcEndpointRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
    private final JdbcEventRepository events;
    private final JdbcEndpointRepository endpoints;

    public EventService(JdbcEventRepository events, JdbcEndpointRepository endpoints) {
        this.events = events;
        this.endpoints = endpoints;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public EventReceipt accept(EventRequest request) {
        // shortcut: API 키 인증 도입 전 로컬 실습 프로젝트 1로 고정한다.
        long projectId = 1;
        endpoints.lockProject(projectId);
        var existing = events.findExisting(projectId, request);
        if (existing.isPresent()) {
            var event = existing.get();
            if (!event.sameContent()) {
                throw new EventRejected(HttpStatus.CONFLICT, "EVENT_ID_CONFLICT", "같은 eventId에 다른 내용이 접수됐습니다.");
            }
            return new EventReceipt(event.id(), event.eventId(), event.matchedDeliveryCount(), true);
        }
        if (events.countByProject(projectId) >= 10000) {
            throw new EventRejected(HttpStatus.CONFLICT, "RESOURCE_LIMIT", "프로젝트의 이벤트 한도에 도달했습니다.");
        }
        long id = events.insert(projectId, request);
        int count = events.createDeliveries(id, projectId, request);
        return new EventReceipt(id, request.eventId(), count, false);
    }
}
