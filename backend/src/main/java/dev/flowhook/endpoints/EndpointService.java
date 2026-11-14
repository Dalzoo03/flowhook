package dev.flowhook.endpoints;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import dev.flowhook.deliveries.LocalReceiverPolicy;

@Service
public class EndpointService {
    private final JdbcEndpointRepository repository;
    private final LocalReceiverPolicy localReceiver;

    public EndpointService(JdbcEndpointRepository repository, LocalReceiverPolicy localReceiver) {
        this.repository = repository;
        this.localReceiver = localReceiver;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public Endpoint create(long projectId, String name, String url, String eventType) {
        requireProject(projectId);
        if (name == null || name.strip().isEmpty() || name.strip().length() > 80) {
            throw badRequest("이름은 공백을 제외하고 1~80자여야 합니다.");
        }
        if (eventType == null || eventType.isBlank()) {
            throw badRequest("구독 이벤트 유형이 필요합니다.");
        }
        requireHttpsUrl(url);
        repository.lockProject(projectId);
        if (repository.countByProjectId(projectId) >= 10) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "프로젝트당 전달 대상은 최대 10개입니다.");
        }
        return repository.save(projectId, name.strip(), url, eventType);
    }

    public List<Endpoint> findAll(long projectId) {
        requireProject(projectId);
        return repository.findAllByProjectId(projectId);
    }

    private void requireProject(long projectId) {
        // shortcut: 프로젝트 생성·인증 구현 전에는 로컬 실습 프로젝트 1만 제공한다.
        if (projectId != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.");
        }
    }

    private void requireHttpsUrl(String value) {
        if (localReceiver.allows(value)) return;
        if (value == null || value.isBlank()) throw badRequest("전달 URL이 필요합니다.");
        try {
            URI url = new URI(value);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                    || url.getRawUserInfo() != null || url.getRawFragment() != null
                    || url.getPort() == 0 || url.getPort() > 65535) {
                throw badRequest("사용자 정보·fragment가 없는 유효한 HTTPS URL이 필요합니다.");
            }
        } catch (URISyntaxException exception) {
            throw badRequest("유효한 HTTPS URL이 필요합니다.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
