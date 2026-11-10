package dev.flowhook.events;

public record EventReceipt(long id, String eventId, int matchedDeliveryCount, boolean duplicate) {
}
