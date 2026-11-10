package dev.flowhook.endpoints;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEndpointRepository {
    private final JdbcClient jdbc;

    public JdbcEndpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockProject(long projectId) {
        // 같은 프로젝트의 등록을 DB 트랜잭션이 끝날 때까지 순서대로 처리한다.
        jdbc.sql("SELECT pg_advisory_xact_lock(:projectId)")
                .param("projectId", projectId).query((rs, rowNum) -> 0).single();
    }

    public long countByProjectId(long projectId) {
        return jdbc.sql("SELECT count(*) FROM public.endpoints WHERE project_id = :projectId")
                .param("projectId", projectId).query(Long.class).single();
    }

    public Endpoint save(long projectId, String name, String url, String eventType) {
        return jdbc.sql("""
                INSERT INTO public.endpoints (project_id, name, url, event_type)
                VALUES (:projectId, :name, :url, :eventType)
                RETURNING id, project_id, name, url, event_type, active
                """)
                .param("projectId", projectId).param("name", name)
                .param("url", url).param("eventType", eventType)
                .query(Endpoint.class).single();
    }

    public List<Endpoint> findAllByProjectId(long projectId) {
        return jdbc.sql("""
                SELECT id, project_id, name, url, event_type, active
                FROM public.endpoints WHERE project_id = :projectId ORDER BY id
                """)
                .param("projectId", projectId).query(Endpoint.class).list();
    }
}
