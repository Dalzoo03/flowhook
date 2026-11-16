package dev.flowhook.endpoints;

import jakarta.persistence.*;

@Entity
@Table(name = "endpoints", schema = "public")
public class EndpointEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long projectId;
    private String name;
    private String url;
    private String eventType;
    private boolean active = true;

    protected EndpointEntity() { }

    public EndpointEntity(long projectId, String name, String url, String eventType) {
        this.projectId = projectId;
        this.name = name;
        this.url = url;
        this.eventType = eventType;
    }

    public Endpoint toResponse() { return new Endpoint(id, projectId, name, url, eventType, active); }
}
