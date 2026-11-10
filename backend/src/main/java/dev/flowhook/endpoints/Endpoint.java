package dev.flowhook.endpoints;

public record Endpoint(long id, long projectId, String name, String url, String eventType, boolean active) {
}
