package dev.flowhook.events;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EventApiTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-bookworm");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private static final String BODY = """
            {"eventId":"order-1001","type":"order.created","data":{"orderId":1001}}
            """;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("TRUNCATE public.endpoints, public.events RESTART IDENTITY CASCADE").update();
    }

    private void endpoint(String type, boolean active, long projectId) {
        jdbc.sql("INSERT INTO public.endpoints (project_id, name, url, event_type, active) VALUES (:project, '테스트', 'https://example.com/hooks', :type, :active)")
                .param("project", projectId).param("type", type).param("active", active).update();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM public." + table).query(Long.class).single();
    }

    @Test
    void createsEventAndOnlyMatchingActiveProjectDeliveries() throws Exception {
        endpoint("order.created", true, 1);
        endpoint("order.created", true, 1);
        endpoint("Order.created", true, 1);
        endpoint("order.created", false, 1);
        endpoint("order.created", true, 2);
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.eventId").value("order-1001"))
                .andExpect(jsonPath("$.matchedDeliveryCount").value(2))
                .andExpect(jsonPath("$.duplicate").value(false));
        assertEquals(1, count("events"));
        assertEquals(2, count("deliveries"));
        assertEquals(2L, jdbc.sql("SELECT count(*) FROM public.deliveries WHERE status = 'PENDING' AND target_url = 'https://example.com/hooks' AND payload->>'eventId' = 'order-1001' AND payload->'data' = '{\"orderId\":1001}'::jsonb").query(Long.class).single());
    }

    @Test
    void acceptsWithoutDestinations() throws Exception {
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.matchedDeliveryCount").value(0));
        assertEquals(1, count("events"));
        assertEquals(0, count("deliveries"));
    }

    @Test
    void duplicateKeepsOriginalDestinationsAndFrozenUrl() throws Exception {
        endpoint("order.created", true, 1);
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isAccepted());
        jdbc.sql("UPDATE public.endpoints SET url='https://changed.example.com', active=false").update();
        endpoint("order.created", true, 1);
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.matchedDeliveryCount").value(1));
        assertEquals(1, count("events"));
        assertEquals(1, count("deliveries"));
        assertEquals("https://example.com/hooks", jdbc.sql("SELECT target_url FROM public.deliveries").query(String.class).single());
    }

    @Test
    void comparesObjectsAndNumbersExactly() throws Exception {
        String original = "{\"eventId\":\"exact\",\"type\":\"x\",\"data\":{\"n\":9007199254740993,\"d\":1,\"nested\":{\"b\":2,\"a\":1}}}";
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(original)).andExpect(status().isAccepted());
        String equivalent = "{\"eventId\":\"exact\",\"type\":\"x\",\"data\":{\"nested\":{\"a\":1.0,\"b\":2},\"d\":1.0,\"n\":9007199254740993}}";
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(equivalent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(original.replace("9007199254740993", "9007199254740992")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_ID_CONFLICT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":[1,2]}", "{\"a\":\"1\"}", "{\"a\":true}", "{\"a\":null}"})
    void differentJsonTypesOrArrayOrderConflict(String data) throws Exception {
        String original = "{\"eventId\":\"types\",\"type\":\"x\",\"data\":{\"a\":[2,1]}}";
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(original)).andExpect(status().isAccepted());
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"types\",\"type\":\"x\",\"data\":" + data + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    void changedTypeConflicts() throws Exception {
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isAccepted());
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("order.created", "Order.created")))
                .andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{", "{} {}",
            "{\"eventId\":1,\"type\":\"x\",\"data\":{}}",
            "{\"eventId\":\" \",\"type\":\"x\",\"data\":{}}",
            "{\"eventId\":\"x\",\"type\":\" \",\"data\":{}}",
            "{\"eventId\":\"x\",\"type\":\"x\",\"data\":[]}",
            "{\"eventId\":\"x\",\"type\":\"x\",\"data\":null}",
            "{\"eventId\":\"x\",\"type\":\"x\",\"data\":{},\"projectId\":2}",
            "{\"eventId\":\"x\",\"eventId\":\"y\",\"type\":\"x\",\"data\":{}}",
            "{\"eventId\":\"x\",\"type\":\"x\",\"data\":{\"a\":1,\"a\":2}}",
            "{\"eventId\":\"x\",\"type\":\"x\",\"data\":{\"a\":NaN}}",
            "{\"eventId\":\"x\",\"type\":\"x\",\"data\":{\"a\":1e999999}}",
            "{\"eventId\":\"x\",\"type\":\"x\",\"data\":{\"a\":\"\\u0000\"}}"
    })
    void rejectsInvalidRequestsWithoutSaving(String body) throws Exception {
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EVENT"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertEquals(0, count("events"));
    }

    @Test
    void enforcesIdentifierAndUtf8BodyLimits() throws Exception {
        String prefix = "{\"eventId\":\"size\",\"type\":\"x\",\"data\":{\"text\":\"";
        String suffix = "\"}}";
        int padding = 65536 - prefix.getBytes(StandardCharsets.UTF_8).length - suffix.length() - 3;
        String exact = prefix + "가" + "a".repeat(padding) + suffix;
        assertEquals(65536, exact.getBytes(StandardCharsets.UTF_8).length);
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(exact.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted());
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content((exact + " ").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().is(413));
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("order-1001", "a".repeat(129))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("order.created", "a".repeat(129))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rollsBackEventWhenDeliveryInsertFails() throws Exception {
        endpoint("order.created", true, 1);
        jdbc.sql("ALTER TABLE public.deliveries ADD CONSTRAINT test_reject_delivery CHECK (false) NOT VALID").update();
        try {
            mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
            assertEquals(0, count("events"));
            assertEquals(0, count("deliveries"));
        } finally {
            jdbc.sql("ALTER TABLE public.deliveries DROP CONSTRAINT test_reject_delivery").update();
        }
    }

    @Test
    void deduplicatesOneHundredConcurrentRequests() throws Exception {
        endpoint("order.created", true, 1);
        endpoint("order.created", true, 1);
        Callable<Integer> send = () -> mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse().getStatus();
        try (var executor = Executors.newFixedThreadPool(16)) {
            int accepted = 0;
            int duplicate = 0;
            for (var result : executor.invokeAll(Collections.nCopies(100, send))) {
                int status = result.get();
                if (status == 202) accepted++;
                else if (status == 200) duplicate++;
                else fail("Unexpected status " + status);
            }
            assertEquals(1, accepted);
            assertEquals(99, duplicate);
        }
        assertEquals(1, count("events"));
        assertEquals(2, count("deliveries"));
    }

    @Test
    void duplicateWorksAtCapacityButNewEventIsRejected() throws Exception {
        jdbc.sql("INSERT INTO public.events (project_id, event_id, type, data) SELECT 1, 'seed-' || g, 'order.created', '{}'::jsonb FROM generate_series(1, 10000) g").update();
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"seed-1\",\"type\":\"order.created\",\"data\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_LIMIT"));
        assertEquals(10000, count("events"));
    }

    @Test
    void sameClientIdInAnotherProjectDoesNotCollide() throws Exception {
        jdbc.sql("INSERT INTO public.events (project_id, event_id, type, data) VALUES (2, 'order-1001', 'different', '{}'::jsonb)").update();
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.duplicate").value(false));
        assertEquals(2, count("events"));
    }

    @Test
    void acceptsLongNumericTokensAndObjectKeysWithinBodyBudget() throws Exception {
        String body = "{\"eventId\":\"large-number\",\"type\":\"x\",\"data\":{\""
                + "k".repeat(51000) + "\":" + "9".repeat(1001) + "}}";
        assertTrue(body.getBytes(StandardCharsets.UTF_8).length < 65536);
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("9".repeat(1001), "9".repeat(1000) + "8")))
                .andExpect(status().isConflict());
    }

    @Test
    void swaggerProvidesUiAndExecutableEventBody() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v1/events'].post.requestBody.required").value(true))
                .andExpect(jsonPath("$.paths['/v1/events'].post.requestBody.content['application/json'].schema['$ref']")
                        .value("#/components/schemas/EventInput"))
                .andExpect(jsonPath("$.components.schemas.EventInput.required", org.hamcrest.Matchers.containsInAnyOrder("eventId", "type", "data")))
                .andExpect(jsonPath("$.components.schemas.EventInput.additionalProperties").value(false))
                .andExpect(jsonPath("$.paths['/v1/events'].post.responses['202']").exists())
                .andExpect(jsonPath("$.paths['/v1/events'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/v1/events'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/projects/{projectId}/endpoints'].post").exists());
    }
}
