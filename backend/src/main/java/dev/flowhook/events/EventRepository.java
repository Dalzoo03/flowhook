package dev.flowhook.events;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<EventEntity, Long> {
    long countByProjectId(long projectId);

    @Query(value = """
            SELECT id, event_id AS "eventId", matched_delivery_count AS "matchedDeliveryCount",
                   (type = :type AND data = CAST(:data AS jsonb)) AS "sameContent"
            FROM public.events WHERE project_id = :projectId AND event_id = :eventId
            """, nativeQuery = true)
    Optional<ExistingEvent> findExisting(@Param("projectId") long projectId, @Param("eventId") String eventId,
                                       @Param("type") String type, @Param("data") String data);

    @Modifying
    @Query(value = """
            INSERT INTO public.deliveries (event_id, endpoint_id, target_url, payload)
            SELECT :id, id, url,
                   jsonb_build_object('eventId', CAST(:eventId AS text), 'type', CAST(:type AS text), 'data', CAST(:data AS jsonb))
            FROM public.endpoints WHERE project_id = :projectId AND active = true AND event_type = :type
            """, nativeQuery = true)
    int createDeliveries(@Param("id") long id, @Param("projectId") long projectId, @Param("eventId") String eventId,
                         @Param("type") String type, @Param("data") String data);

    interface ExistingEvent {
        Long getId();
        String getEventId();
        int getMatchedDeliveryCount();
        boolean getSameContent();
    }
}
