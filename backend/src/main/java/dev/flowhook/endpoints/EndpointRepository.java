package dev.flowhook.endpoints;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EndpointRepository extends JpaRepository<EndpointEntity, Long> {
    long countByProjectId(long projectId);
    List<EndpointEntity> findAllByProjectIdOrderById(long projectId);

    // 같은 프로젝트의 등록과 이벤트 접수를 트랜잭션 종료까지 직렬화한다.
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:projectId)", nativeQuery = true)
    Integer lockProject(@Param("projectId") long projectId);
}
