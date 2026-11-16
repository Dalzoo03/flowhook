package dev.flowhook.deliveries;

import java.util.List;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class DeliveryTransactions {
    private final EntityManager entityManager;
    private final RetryPolicy retryPolicy;
    public DeliveryTransactions(EntityManager entityManager, RetryPolicy retryPolicy) { this.entityManager = entityManager; this.retryPolicy = retryPolicy; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Claim claim(long projectId, long id) {
        UUID token = UUID.randomUUID();
        Optional<Reserved> reserved = entityManager.unwrap(Session.class).createNativeQuery("""
                UPDATE public.deliveries d SET status = 'PROCESSING',
                    attempt_count = d.attempt_count + 1, next_attempt_at = NULL,
                    lease_token = :token, lease_expires_at = clock_timestamp() + interval '30 seconds'
                FROM public.events e, public.endpoints ep
                WHERE d.event_id = e.id AND d.endpoint_id = ep.id AND e.project_id = :project
                  AND d.id = :id AND ep.active = true AND d.attempt_count < :maxAttempts
                  AND (d.status = 'PENDING' OR (d.status = 'RETRY_WAIT' AND d.next_attempt_at <= clock_timestamp()))
                RETURNING d.id, d.target_url, d.payload::text AS payload, d.attempt_count
                """, Reserved.class).setParameter("project", projectId).setParameter("id", id).setParameter("maxAttempts", RetryPolicy.MAX_ATTEMPTS).setParameter("token", token).getResultStream().findFirst();
        if (reserved.isEmpty()) {
            boolean exists = entityManager.unwrap(Session.class).createNativeQuery("SELECT EXISTS(SELECT 1 FROM public.deliveries d JOIN public.events e ON d.event_id=e.id WHERE d.id=:id AND e.project_id=:project)", Boolean.class)
                    .setParameter("id", id).setParameter("project", projectId).getSingleResult();
            throw new ResponseStatusException(exists ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND,
                    "전달 작업이 없거나 실행 시각·상태·활성 조건을 만족하지 않습니다.");
        }
        var value = reserved.get();
        long attemptId = entityManager.unwrap(Session.class).createNativeQuery("INSERT INTO public.delivery_attempts (delivery_id, attempt_no) VALUES (:id, :number) RETURNING id", Long.class)
                .setParameter("id", id).setParameter("number", value.attemptCount()).getSingleResult();
        return new Claim(id, attemptId, value.targetUrl(), value.payload(), value.attemptCount(), token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public DeliveryResult finish(Claim claim, LocalWebhookSender.SendResult result) {
        // 회수와 같은 delivery→attempt 순서로 잠그고 만료된 소유자의 결과를 거절한다.
        var owned = entityManager.unwrap(Session.class).createNativeQuery("""
                SELECT id FROM public.deliveries
                WHERE id=:id AND status='PROCESSING' AND attempt_count=:number
                  AND lease_token=:token AND lease_expires_at > clock_timestamp()
                FOR UPDATE
                """, Long.class).setParameter("id", claim.id()).setParameter("number", claim.attemptCount())
                .setParameter("token", claim.leaseToken()).getResultList();
        if (owned.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "처리 권한이 없거나 만료됐습니다.");
        Optional<Instant> finished = entityManager.unwrap(Session.class).createNativeQuery("""
                UPDATE public.delivery_attempts
                SET finished_at=clock_timestamp(), outcome=:outcome, http_status=:code,
                    duration_ms=:duration, response_body=:body, response_truncated=:truncated
                WHERE id=:attempt AND delivery_id=:delivery AND attempt_no=:number AND finished_at IS NULL
                RETURNING finished_at
                """, Instant.class).setParameter("outcome", result.outcome()).setParameter("code", result.httpStatus())
                .setParameter("duration", result.durationMs()).setParameter("body", result.body()).setParameter("truncated", result.truncated())
                .setParameter("attempt", claim.attemptId()).setParameter("delivery", claim.id()).setParameter("number", claim.attemptCount())
                .getResultStream().findFirst();
        if (finished.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 완료되었거나 다른 시도입니다.");
        var decision = retryPolicy.decide(result, claim.attemptCount(), finished.get());
        Optional<Completion> delivery = entityManager.unwrap(Session.class).createNativeQuery("""
                UPDATE public.deliveries SET status=:state, lease_token=NULL, lease_expires_at=NULL,
                    next_attempt_at=CASE WHEN CAST(:delay AS bigint) IS NULL THEN NULL
                        ELSE clock_timestamp() + CAST(:delay AS bigint) * interval '1 millisecond' END
                WHERE id=:id AND status='PROCESSING' AND attempt_count=:number
                  AND lease_token=:token AND lease_expires_at > clock_timestamp()
                RETURNING status, attempt_count, next_attempt_at
                """, Completion.class).setParameter("state", decision.status()).setParameter("delay", decision.delayMs())
                .setParameter("id", claim.id()).setParameter("number", claim.attemptCount()).setParameter("token", claim.leaseToken()).getResultStream().findFirst();
        if (delivery.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "전달 작업 상태가 변경됐습니다.");
        var saved = delivery.get();
        return new DeliveryResult(claim.id(), claim.attemptId(), saved.status(), result.outcome(), result.httpStatus(), result.durationMs(), saved.attemptCount(), saved.nextAttemptAt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public int recoverExpired(List<String> targets) {
        var expired = entityManager.unwrap(Session.class).createNativeQuery("""
                SELECT d.id, d.attempt_count, d.lease_token FROM public.deliveries d
                JOIN public.events e ON e.id=d.event_id
                WHERE e.project_id=1 AND d.status='PROCESSING'
                  AND d.lease_expires_at <= clock_timestamp() AND d.lease_token IS NOT NULL
                  AND d.attempt_count BETWEEN 1 AND :maxAttempts AND d.target_url IN (:targets)
                ORDER BY d.lease_expires_at, d.id LIMIT 10 FOR UPDATE OF d SKIP LOCKED
                """, Expired.class).setParameter("maxAttempts", RetryPolicy.MAX_ATTEMPTS)
                .setParameterList("targets", targets).getResultList();
        for (var job : expired) {
            int closed = entityManager.unwrap(Session.class).createNativeMutationQuery("""
                    UPDATE public.delivery_attempts SET outcome='UNKNOWN', finished_at=clock_timestamp(), recovery_reason='LEASE_EXPIRED'
                    WHERE delivery_id=:id AND attempt_no=:number AND finished_at IS NULL
                    """).setParameter("id", job.id()).setParameter("number", job.attemptCount()).executeUpdate();
            if (closed != 1) throw new IllegalStateException("Expired delivery has no unfinished attempt");
            var decision = retryPolicy.afterLeaseExpiry(job.attemptCount());
            int changed = entityManager.unwrap(Session.class).createNativeMutationQuery("""
                    UPDATE public.deliveries SET status=:state, lease_token=NULL, lease_expires_at=NULL,
                        next_attempt_at=CASE WHEN CAST(:delay AS bigint) IS NULL THEN NULL
                            ELSE clock_timestamp() + CAST(:delay AS bigint) * interval '1 millisecond' END
                    WHERE id=:id AND status='PROCESSING' AND lease_token=:token
                    """).setParameter("state", decision.status()).setParameter("delay", decision.delayMs())
                    .setParameter("id", job.id()).setParameter("token", job.leaseToken()).executeUpdate();
            if (changed != 1) throw new IllegalStateException("Expired delivery ownership changed");
        }
        return expired.size();
    }

    @Transactional(readOnly = true, timeout = 5)
    public List<Long> findReady(List<String> targets) {
        return entityManager.unwrap(Session.class).createNativeQuery("""
                SELECT d.id FROM public.deliveries d
                JOIN public.events e ON e.id=d.event_id
                JOIN public.endpoints ep ON ep.id=d.endpoint_id AND ep.project_id=e.project_id
                WHERE e.project_id=1 AND ep.active=true AND d.attempt_count < :maxAttempts
                  AND d.target_url IN (:targets)
                  AND (d.status='PENDING' OR (d.status='RETRY_WAIT' AND d.next_attempt_at <= clock_timestamp()))
                ORDER BY COALESCE(d.next_attempt_at, d.created_at), d.id
                LIMIT 10
                """, Long.class).setParameter("maxAttempts", RetryPolicy.MAX_ATTEMPTS)
                .setParameterList("targets", targets).getResultList();
    }

    @Transactional(readOnly = true)
    public List<DeliveryView> list(long projectId) {
        return entityManager.unwrap(Session.class).createNativeQuery("""
                SELECT d.id, d.event_id, d.endpoint_id, d.target_url, d.status, a.outcome, a.http_status, a.duration_ms,
                       d.attempt_count, d.next_attempt_at, d.lease_expires_at, a.recovery_reason
                FROM public.deliveries d JOIN public.events e ON e.id=d.event_id
                LEFT JOIN LATERAL (
                    SELECT outcome, http_status, duration_ms, recovery_reason FROM public.delivery_attempts
                    WHERE delivery_id=d.id ORDER BY attempt_no DESC LIMIT 1
                ) a ON true
                WHERE e.project_id=:project ORDER BY d.id DESC LIMIT 100
                """, DeliveryView.class).setParameter("project", projectId).getResultList();
    }

    record Reserved(long id, String targetUrl, String payload, int attemptCount) { }
    record Expired(long id, int attemptCount, UUID leaseToken) { }
    record Completion(String status, int attemptCount, Instant nextAttemptAt) { }
    public record Claim(long id, long attemptId, String targetUrl, String payload, int attemptCount, UUID leaseToken) { }
    public record DeliveryResult(long deliveryId, long attemptId, String status, String outcome, Integer httpStatus, long durationMs, int attemptCount, Instant nextAttemptAt) { }
    public record DeliveryView(long id, long eventId, long endpointId, String targetUrl, String status, String outcome, Integer httpStatus, Long durationMs, int attemptCount, Instant nextAttemptAt, Instant leaseExpiresAt, String recoveryReason) { }
}
