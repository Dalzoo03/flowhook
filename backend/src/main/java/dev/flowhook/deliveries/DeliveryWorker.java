package dev.flowhook.deliveries;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;

public class DeliveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(DeliveryWorker.class);
    private final DeliveryTransactions transactions;
    private final DeliveryService service;
    private final LocalReceiverPolicy policy;

    public DeliveryWorker(DeliveryTransactions transactions, DeliveryService service, LocalReceiverPolicy policy) {
        this.transactions = transactions;
        this.service = service;
        this.policy = policy;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void recoverExpired() {
        try {
            transactions.recoverExpired(policy.workerTargets());
        } catch (RuntimeException exception) {
            LOG.warn("Delivery recovery failed type={}", exception.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void runOnce() {
        List<Long> candidates;
        try {
            candidates = transactions.findReady(policy.workerTargets());
        } catch (RuntimeException exception) {
            LOG.warn("Delivery worker lookup failed type={}", exception.getClass().getSimpleName());
            return;
        }
        // 후보는 소유권이 아니다. 실제 HTTP 직전에 기존 claim으로 한 건씩 선점한다.
        for (long id : candidates) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                service.sendOnce(1, id);
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode().value() != 409 && exception.getStatusCode().value() != 404) {
                    LOG.warn("Delivery worker rejected id={} status={}", id, exception.getStatusCode().value());
                }
            } catch (RuntimeException exception) {
                LOG.warn("Delivery worker failed id={} type={}", id, exception.getClass().getSimpleName());
            }
        }
    }
}
