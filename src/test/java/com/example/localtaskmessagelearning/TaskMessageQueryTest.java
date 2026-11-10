package com.example.localtaskmessagelearning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskMessageQueryTest {

    private static final String[] STATES = {"PENDING", "PROCESSING", "SENT", "FAILED"};

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = createQueryDatabase();
        mvc = MockMvcBuilders.standaloneSetup(
                new TaskMessageAdminController(new TaskMessageAdminService(jdbc))
        ).build();
    }

    static JdbcTemplate createQueryDatabase() {
        var source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:query-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE local_task_message (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(64) NOT NULL UNIQUE,
                    biz_id VARCHAR(64) NOT NULL,
                    topic VARCHAR(128) NOT NULL,
                    payload VARCHAR(8192) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_error VARCHAR(512),
                    lock_token VARCHAR(64),
                    locked_until TIMESTAMP(3),
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        return jdbc;
    }

    @AfterEach
    void closeDatabase() {
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @ParameterizedTest(name = "只返回状态为{0}的任务")
    @ValueSource(strings = {"PENDING", "PROCESSING", "SENT", "FAILED"})
    void shouldFilterTasksByStatus(String state) throws Exception {
        for (String candidate : STATES) {
            insert("TASK-" + candidate, candidate);
        }

        var response = mvc.perform(get("/task-messages").param("status", state))
                .andExpect(status().isOk()).andReturn().getResponse();
        var tasks = jsonMapper.readTree(response.getContentAsString());

        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.get(0).get("taskId").asString()).isEqualTo("TASK-" + state);
        assertThat(tasks.get(0).get("status").asString()).isEqualTo(state);
        assertThat(tasks.get(0).has("payload")).isFalse();
        assertThat(tasks.get(0).has("lockToken")).isFalse();
    }

    @Test
    void shouldReturnNewestTwentyMatchingTasks() throws Exception {
        for (int index = 0; index < 25; index++) {
            insert("FAILED-" + index, "FAILED");
        }
        insert("NEWER-SENT", "SENT");

        var response = mvc.perform(get("/task-messages").param("status", "FAILED"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var tasks = jsonMapper.readTree(response.getContentAsString());

        assertThat(tasks.size()).isEqualTo(20);
        assertThat(tasks.get(0).get("taskId").asString()).isEqualTo("FAILED-24");
        assertThat(tasks.get(19).get("taskId").asString()).isEqualTo("FAILED-5");
    }

    @Test
    void shouldReturnRetryAndLeaseDetails() throws Exception {
        insert("DETAIL", "PROCESSING");
        LocalDateTime nextRetry = LocalDateTime.of(2026, 11, 9, 10, 0);
        LocalDateTime lease = nextRetry.plusSeconds(30);
        jdbc.update("""
                UPDATE local_task_message
                SET retry_count = 2, next_retry_time = ?, locked_until = ?,
                    lock_token = 'internal-owner', last_error = 'PREVIOUS_FAILURE'
                WHERE task_id = 'DETAIL'
                """, nextRetry, lease);

        var response = mvc.perform(get("/task-messages").param("status", "PROCESSING"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var task = jsonMapper.readTree(response.getContentAsString()).get(0);

        assertThat(task.get("bizId").asString()).isEqualTo("ORDER-DETAIL");
        assertThat(task.get("topic").asString()).isEqualTo("order_created");
        assertThat(task.get("retryCount").asInt()).isEqualTo(2);
        assertThat(LocalDateTime.parse(task.get("nextRetryTime").asString())).isEqualTo(nextRetry);
        assertThat(LocalDateTime.parse(task.get("lockedUntil").asString())).isEqualTo(lease);
        assertThat(task.get("lastError").asString()).isEqualTo("PREVIOUS_FAILURE");
        assertThat(task.get("updateTime").isNull()).isFalse();
        assertThat(task.has("lockToken")).isFalse();
    }

    @ParameterizedTest(name = "拒绝非法查询状态 [{index}]")
    @ValueSource(strings = {"UNKNOWN", "", "pending", "FAILED' OR '1'='1"})
    void shouldRejectInvalidStatus(String state) throws Exception {
        mvc.perform(get("/task-messages").param("status", state))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRequireStatusParameter() throws Exception {
        mvc.perform(get("/task-messages")).andExpect(status().isBadRequest());
    }

    @Test
    void shouldCountEachStatusAcrossAllTasks() throws Exception {
        for (int state = 0; state < STATES.length; state++) {
            for (int index = 0; index <= state; index++) {
                insert(STATES[state] + "-" + index, STATES[state]);
            }
        }

        var response = mvc.perform(get("/task-messages/stats"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var counts = jsonMapper.readTree(response.getContentAsString());

        assertThat(counts.size()).isEqualTo(4);
        for (int state = 0; state < STATES.length; state++) {
            assertThat(counts.get(STATES[state]).asLong()).isEqualTo(state + 1L);
        }
    }

    @Test
    void shouldReturnZeroCountsForEmptyTable() throws Exception {
        var response = mvc.perform(get("/task-messages/stats"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var counts = jsonMapper.readTree(response.getContentAsString());

        assertThat(counts.size()).isEqualTo(4);
        for (String state : STATES) {
            assertThat(counts.get(state).asLong()).isZero();
        }
    }

    @Test
    void shouldFillMissingStatusCountsWithZero() throws Exception {
        insert("ONLY-SENT", "SENT");

        var response = mvc.perform(get("/task-messages/stats"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var counts = jsonMapper.readTree(response.getContentAsString());

        assertThat(counts.size()).isEqualTo(4);
        assertThat(counts.get("SENT").asLong()).isEqualTo(1);
        for (String state : new String[]{"PENDING", "PROCESSING", "FAILED"}) {
            assertThat(counts.get(state).asLong()).isZero();
        }
    }

    private void insert(String taskId, String state) {
        jdbc.update("""
                INSERT INTO local_task_message (task_id, biz_id, topic, payload, status)
                VALUES (?, ?, 'order_created', '{}', ?)
                """, taskId, "ORDER-" + taskId, state);
    }
}
