package dev.flowhook.events;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "events", schema = "public")
public class EventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long projectId;
    private String eventId;
    private String type;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String data;
    private int matchedDeliveryCount;

    protected EventEntity() { }

    public EventEntity(long projectId, String eventId, String type, String data) {
        this.projectId = projectId;
        this.eventId = eventId;
        this.type = type;
        this.data = data;
    }

    public Long getId() { return id; }
    public void setMatchedDeliveryCount(int count) { this.matchedDeliveryCount = count; }
}
