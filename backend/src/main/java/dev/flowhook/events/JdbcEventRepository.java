package dev.flowhook.events;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEventRepository {
    private final JdbcClient jdbc;

    public JdbcEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ExistingEvent> findExisting(long projectId, EventRequest request) {
        return jdbc.sql("""
                SELECT id, event_id, matched_delivery_count,
                       (type = :type AND data = CAST(:data AS jsonb)) AS same_content
                FROM public.events WHERE project_id = :projectId AND event_id = :eventId
                """)
                .param("projectId", projectId).param("eventId", request.eventId())
                .param("type", request.type()).param("data", request.data())
                .query(ExistingEvent.class).optional();
    }

    long countByProject(long projectId) {
        return jdbc.sql("SELECT count(*) FROM public.events WHERE project_id = :projectId")
                .param("projectId", projectId).query(Long.class).single();
    }

    long insert(long projectId, EventRequest request) {
        return jdbc.sql("""
                INSERT INTO public.events (project_id, event_id, type, data)
                VALUES (:projectId, :eventId, :type, CAST(:data AS jsonb)) RETURNING id
                """)
                .param("projectId", projectId).param("eventId", request.eventId())
                .param("type", request.type()).param("data", request.data()).query(Long.class).single();
    }

    int createDeliveries(long id, long projectId, EventRequest request) {
        int count = jdbc.sql("""
                INSERT INTO public.deliveries (event_id, endpoint_id, target_url, payload)
                SELECT :id, id, url,
                       jsonb_build_object('eventId', CAST(:eventId AS text), 'type', CAST(:type AS text), 'data', CAST(:data AS jsonb))
                FROM public.endpoints
                WHERE project_id = :projectId AND active = true AND event_type = :type
                """)
                .param("id", id).param("projectId", projectId).param("eventId", request.eventId())
                .param("type", request.type()).param("data", request.data()).update();
        jdbc.sql("UPDATE public.events SET matched_delivery_count = :count WHERE id = :id")
                .param("count", count).param("id", id).update();
        return count;
    }

    record ExistingEvent(long id, String eventId, int matchedDeliveryCount, boolean sameContent) {
    }
}
