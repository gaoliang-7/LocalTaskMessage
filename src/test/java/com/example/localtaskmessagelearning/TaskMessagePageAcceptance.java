package com.example.localtaskmessagelearning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskMessagePageAcceptance {
    private static final String PAGE_URL = "/task-messages/page";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = TaskMessageQueryTest.createQueryDatabase();
        mvc = MockMvcBuilders.standaloneSetup(
                new TaskMessageAdminController(new TaskMessageAdminService(jdbc))
        ).build();
    }

    @AfterEach
    void closeDatabase() {
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void shouldContinueAfterLastReturnedRowEvenWhenNewTasksArrive() throws Exception {
        insertTask(10, "FAILED");
        insertTask(21, "SENT");
        insertTask(30, "FAILED");
        insertTask(40, "FAILED");
        insertTask(50, "PENDING");
        insertTask(80, "FAILED");

        JsonNode first = requestPage(get(PAGE_URL).param("status", "FAILED").param("limit", "2"));
        assertThat(first.get("items").size()).isEqualTo(2);
        assertThat(first.get("items").get(0).get("taskId").asString()).isEqualTo("TASK-80");
        assertThat(first.get("items").get(1).get("taskId").asString()).isEqualTo("TASK-40");
        assertThat(first.get("nextBeforeId").asString()).isEqualTo("40");

        insertTask(99, "FAILED");
        JsonNode second = requestPage(get(PAGE_URL)
                .param("status", "FAILED")
                .param("beforeId", first.get("nextBeforeId").asString())
                .param("limit", "2"));

        assertThat(second.get("items").size()).isEqualTo(2);
        assertThat(second.get("items").get(0).get("taskId").asString()).isEqualTo("TASK-30");
        assertThat(second.get("items").get(1).get("taskId").asString()).isEqualTo("TASK-10");
        assertThat(second.get("nextBeforeId").isNull()).isTrue();
    }

    @Test
    void shouldDefaultToTwentyItems() throws Exception {
        for (int id = 1; id <= 21; id++) {
            insertTask(id, "PENDING");
        }

        JsonNode page = requestPage(get(PAGE_URL).param("status", "PENDING"));
        assertThat(page.get("items").size()).isEqualTo(20);
        assertThat(page.get("items").get(0).get("taskId").asString()).isEqualTo("TASK-21");
        assertThat(page.get("items").get(19).get("taskId").asString()).isEqualTo("TASK-2");
        assertThat(page.get("nextBeforeId").asString()).isEqualTo("2");
    }

    @Test
    void shouldReturnEmptyPageWhenNoMatchingRowsRemain() throws Exception {
        insertTask(10, "SENT");
        insertTask(30, "FAILED");

        JsonNode page = requestPage(get(PAGE_URL).param("status", "FAILED")
                .param("beforeId", "25").param("limit", "100"));

        assertThat(page.get("items").isArray()).isTrue();
        assertThat(page.get("items").size()).isZero();
        assertThat(page.get("nextBeforeId").isNull()).isTrue();
    }

    @Test
    void shouldReturnBigintCursorAsExactString() throws Exception {
        insertTask(1, "SENT");
        insertTask(Long.MAX_VALUE, "SENT");

        JsonNode page = requestPage(get(PAGE_URL).param("status", "SENT").param("limit", "1"));

        assertThat(page.get("items").get(0).get("taskId").asString())
                .isEqualTo("TASK-" + Long.MAX_VALUE);
        assertThat(page.get("nextBeforeId").isString()).isTrue();
        assertThat(page.get("nextBeforeId").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
    }

    @ParameterizedTest(name = "拒绝 {0}={1}")
    @CsvSource({
            "limit, 0",
            "limit, 101",
            "limit, abc",
            "beforeId, 0",
            "beforeId, -1",
            "beforeId, abc",
            "status, UNKNOWN"
    })
    void shouldRejectInvalidParameters(String parameter, String value) throws Exception {
        var request = get(PAGE_URL).param(parameter, value);
        if (!"status".equals(parameter)) {
            request.param("status", "FAILED");
        }
        mvc.perform(request).andExpect(status().isBadRequest());
    }

    @Test
    void shouldRequireStatus() throws Exception {
        mvc.perform(get(PAGE_URL)).andExpect(status().isBadRequest());
    }

    private JsonNode requestPage(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        return jsonMapper.readTree(response.getContentAsString());
    }

    private void insertTask(long id, String state) {
        jdbc.update("""
                INSERT INTO local_task_message (id, task_id, biz_id, topic, payload, status)
                VALUES (?, ?, ?, 'order_created', '{}', ?)
                """, id, "TASK-" + id, "ORDER-" + id, state);
    }
}
