package dev.flowhook.deliveries;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeliveryService {
    private final DeliveryTransactions transactions;
    private final LocalWebhookSender sender;

    public DeliveryService(DeliveryTransactions transactions, LocalWebhookSender sender) {
        this.transactions = transactions;
        this.sender = sender;
    }

    @Transactional(propagation = Propagation.NEVER)
    public DeliveryTransactions.DeliveryResult sendOnce(long projectId, long id) {
        if (projectId != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var claim = transactions.claim(projectId, id);
        var result = sender.send(claim.id(), claim.targetUrl(), claim.payload());
        return transactions.finish(claim, result);
    }
}
