package dev.flowhook.deliveries;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeliveryTransactions {
    private final JdbcClient jdbc;
    public DeliveryTransactions(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Claim claim(long projectId, long id) {
        var reserved = jdbc.sql("""
                UPDATE public.deliveries d SET status = 'PROCESSING'
                FROM public.events e, public.endpoints ep
                WHERE d.event_id = e.id AND d.endpoint_id = ep.id AND e.project_id = :project
                  AND d.id = :id AND d.status = 'PENDING' AND ep.active = true
                RETURNING d.id, d.target_url, d.payload::text AS payload
                """).param("project", projectId).param("id", id).query(Reserved.class).optional();
        if (reserved.isEmpty()) {
            boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM public.deliveries d JOIN public.events e ON d.event_id=e.id WHERE d.id=:id AND e.project_id=:project)")
                    .param("id", id).param("project", projectId).query(Boolean.class).single();
            throw new ResponseStatusException(exists ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND,
                    "전달 작업이 없거나 활성 PENDING 상태가 아닙니다.");
        }
        long attemptId = jdbc.sql("INSERT INTO public.delivery_attempts (delivery_id) VALUES (:id) RETURNING id")
                .param("id", id).query(Long.class).single();
        var value = reserved.get();
        return new Claim(id, attemptId, value.targetUrl(), value.payload());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public DeliveryResult finish(Claim claim, LocalWebhookSender.SendResult result) {
        String state = result.outcome().equals("SUCCESS") ? "SUCCEEDED" : "DEAD";
        int attempt = jdbc.sql("""
                UPDATE public.delivery_attempts
                SET finished_at=clock_timestamp(), outcome=:outcome, http_status=:code,
                    duration_ms=:duration, response_body=:body, response_truncated=:truncated
                WHERE id=:attempt AND delivery_id=:delivery AND finished_at IS NULL
                """).param("outcome", result.outcome()).param("code", result.httpStatus())
                .param("duration", result.durationMs()).param("body", result.body()).param("truncated", result.truncated())
                .param("attempt", claim.attemptId()).param("delivery", claim.id()).update();
        int delivery = jdbc.sql("UPDATE public.deliveries SET status=:state WHERE id=:id AND status='PROCESSING'")
                .param("state", state).param("id", claim.id()).update();
        if (attempt != 1 || delivery != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "전달 작업 상태가 변경됐습니다.");
        return new DeliveryResult(claim.id(), claim.attemptId(), state, result.outcome(), result.httpStatus(), result.durationMs());
    }

    public List<DeliveryView> list(long projectId) {
        return jdbc.sql("""
                SELECT d.id, d.event_id, d.endpoint_id, d.target_url, d.status, a.outcome, a.http_status, a.duration_ms
                FROM public.deliveries d JOIN public.events e ON e.id=d.event_id
                LEFT JOIN public.delivery_attempts a ON a.delivery_id=d.id
                WHERE e.project_id=:project ORDER BY d.id DESC LIMIT 100
                """).param("project", projectId).query(DeliveryView.class).list();
    }

    record Reserved(long id, String targetUrl, String payload) { }
    public record Claim(long id, long attemptId, String targetUrl, String payload) { }
    public record DeliveryResult(long deliveryId, long attemptId, String status, String outcome, Integer httpStatus, long durationMs) { }
    public record DeliveryView(long id, long eventId, long endpointId, String targetUrl, String status, String outcome, Integer httpStatus, Long durationMs) { }
}
