package dev.flowhook.endpoints;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EndpointApiTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-bookworm");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void resetIsolatedTestDatabase() {
        jdbc.sql("TRUNCATE TABLE public.endpoints RESTART IDENTITY").update();
    }
    private static final String PATH = "/api/projects/1/endpoints";
    private static final String VALID = """
            {"name":" 배송 서비스 ","url":"https://partner.example.com/webhooks","eventType":"order.created"}
            """;

    @Autowired
    MockMvc mvc;

    @Test
    void createsAndListsEndpoint() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", PATH + "/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.projectId").value(1))
                .andExpect(jsonPath("$.name").value("배송 서비스"))
                .andExpect(jsonPath("$.eventType").value("order.created"))
                .andExpect(jsonPath("$.active").value(true));
        mvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].url").value("https://partner.example.com/webhooks"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{",
            "{\"name\":\" \",\"url\":\"https://example.com\",\"eventType\":\"order.created\"}",
            "{\"name\":\"demo\",\"url\":\"http://example.com\",\"eventType\":\"order.created\"}",
            "{\"name\":\"demo\",\"url\":\"https://\",\"eventType\":\"order.created\"}",
            "{\"name\":\"demo\",\"url\":\"https://user:password@example.com\",\"eventType\":\"order.created\"}",
            "{\"name\":\"demo\",\"url\":\"https://example.com\",\"eventType\":\" \"}"
    })
    void rejectsInvalidInputWithoutSaving(String body) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH)).andExpect(content().json("[]"));
    }

    @Test
    void rejectsNameLongerThan80Characters() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace(" 배송 서비스 ", "a".repeat(81))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyExposesFixedLearningProject() throws Exception {
        mvc.perform(get("/api/projects/2/endpoints")).andExpect(status().isNotFound());
        mvc.perform(post("/api/projects/2/endpoints").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isNotFound());
        mvc.perform(get(PATH)).andExpect(content().json("[]"));
    }

    @Test
    void acceptsDuplicatesButRejectsEleventhEndpoint() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(i + 1));
        }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict());
        mvc.perform(get(PATH)).andExpect(jsonPath("$.length()").value(10));
    }

    @Test
    void apiReadsDatabaseRowsAndPersistsNewRows() throws Exception {
        jdbc.sql("INSERT INTO public.endpoints (project_id, name, url, event_type) VALUES (1, 'DB 입력', 'https://example.com', 'order.created')").update();
        mvc.perform(get(PATH)).andExpect(jsonPath("$[0].name").value("DB 입력"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated());
        assertEquals(2L, jdbc.sql("SELECT count(*) FROM public.endpoints").query(Long.class).single());
    }

    @Test
    void concurrentRegistrationsNeverExceedTen() throws Exception {
        Callable<Integer> register = () -> mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(VALID)).andReturn().getResponse().getStatus();
        try (var executor = Executors.newFixedThreadPool(12)) {
            var results = executor.invokeAll(Collections.nCopies(20, register));
            int created = 0;
            int rejected = 0;
            for (var result : results) {
                int code = result.get();
                if (code == 201) created++;
                else if (code == 409) rejected++;
                else throw new AssertionError("Unexpected status: " + code);
            }
            assertEquals(10, created);
            assertEquals(10, rejected);
        }
        assertEquals(10L, jdbc.sql("SELECT count(*) FROM public.endpoints").query(Long.class).single());
    }
}
