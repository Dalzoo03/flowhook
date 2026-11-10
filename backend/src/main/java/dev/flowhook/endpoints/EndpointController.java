package dev.flowhook.endpoints;

import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/endpoints")
public class EndpointController {
    private final EndpointService service;

    public EndpointController(EndpointService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Endpoint> create(@PathVariable long projectId, @RequestBody CreateEndpointRequest request) {
        Endpoint endpoint = service.create(projectId, request.name(), request.url(), request.eventType());
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/endpoints/" + endpoint.id()))
                .body(endpoint);
    }

    @GetMapping
    public List<Endpoint> findAll(@PathVariable long projectId) {
        return service.findAll(projectId);
    }

    public record CreateEndpointRequest(String name, String url, String eventType) {
    }
}
