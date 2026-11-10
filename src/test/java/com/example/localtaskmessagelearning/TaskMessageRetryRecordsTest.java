package com.example.localtaskmessagelearning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskMessageRetryRecordsTest {

    private static final String RECORDS_URL = "/task-messages/{taskId}/retry-records";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TaskMessageAdminTest.TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(TaskMessageAdminController.class))
                .setValidator(context.getBean(LocalValidatorFactoryBean.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        try {
            if (context != null) {
                context.close();
            }
        } finally {
            if (jdbc != null) {
                jdbc.execute("SHUTDOWN");
            }
        }
    }

    @Test
    void shouldReadSavedFailureAfterRetryResetsTask() throws Exception {
        insertFailedTask("RETRIED");
        mvc.perform(post("/task-messages/{taskId}/retry", "RETRIED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestedBy":"learning-user","reason":"MQ recovered"}
                                """))
                .andExpect(status().isAccepted());

        var taskAfterRetry = jdbc.queryForMap(
                "SELECT * FROM local_task_message WHERE task_id = ?", "RETRIED");
        assertThat(taskAfterRetry.get("STATUS")).isEqualTo("PENDING");
        assertThat(taskAfterRetry.get("RETRY_COUNT")).isEqualTo(0);
        assertThat(taskAfterRetry.get("LAST_ERROR")).isNull();

        JsonNode records = readRecords("RETRIED");
        assertThat(records.size()).isOne();
        JsonNode record = records.get(0);
        assertThat(record.size()).isEqualTo(5);
        assertThat(record.get("requestedBy").asString()).isEqualTo("learning-user");
        assertThat(record.get("reason").asString()).isEqualTo("MQ recovered");
        assertThat(record.get("previousRetryCount").asInt()).isEqualTo(5);
        assertThat(record.get("previousError").asString()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(record.get("createTime").isNull()).isFalse();
        assertThat(jdbc.queryForMap("SELECT * FROM local_task_message WHERE task_id = ?", "RETRIED"))
                .isEqualTo(taskAfterRetry);
    }

    @Test
    void shouldReadOnlyLatestTwentyForRequestedTaskWhenTimesMatch() throws Exception {
        insertFailedTask("TARGET");
        insertFailedTask("OTHER");
        for (int index = 1; index <= 22; index++) {
            insertRecord("TARGET", "retry-" + index);
        }
        insertRecord("OTHER", "another task");

        JsonNode records = readRecords("TARGET");
        assertThat(records.size()).isEqualTo(20);
        for (int index = 0; index < records.size(); index++) {
            assertThat(records.get(index).get("reason").asString()).isEqualTo("retry-" + (22 - index));
        }
        assertThat(records.get(0).get("previousError").isNull()).isTrue();
        assertThat(records.get(0).get("createTime").asString()).isEqualTo("2026-01-01T09:30:00");
    }

    @Test
    void shouldUseStoredTaskIdWhenTablesCompareCaseDifferently() throws Exception {
        jdbc.execute("ALTER TABLE local_task_message ALTER COLUMN task_id VARCHAR_IGNORECASE(64) NOT NULL");
        insertFailedTask("Mixed-Case");
        insertRecord("Mixed-Case", "correct spelling");

        JsonNode records = readRecords("mixed-case");
        assertThat(records.size()).isOne();
        assertThat(records.get(0).get("reason").asString()).isEqualTo("correct spelling");
    }

    @Test
    void shouldReturnEmptyArrayWhenExistingTaskHasNoHistory() throws Exception {
        insertFailedTask("NEW-FAILURE");

        assertThat(readRecords("NEW-FAILURE").size()).isZero();
    }

    @Test
    void shouldReturn404WhenTaskDoesNotExist() throws Exception {
        insertRecord("MISSING", "old record");

        mvc.perform(get(RECORDS_URL, "MISSING")).andExpect(status().isNotFound());
    }

    private JsonNode readRecords(String taskId) throws Exception {
        var response = mvc.perform(get(RECORDS_URL, taskId))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        JsonNode records = jsonMapper.readTree(response.getContentAsString());
        assertThat(records.isArray()).isTrue();
        return records;
    }

    private void insertFailedTask(String taskId) {
        jdbc.update("""
                INSERT INTO local_task_message (
                    task_id, biz_id, topic, payload, status, retry_count, last_error
                ) VALUES (?, ?, 'order_created', '{}', 'FAILED', 5, 'BROKER_UNAVAILABLE')
                """, taskId, "ORDER-" + taskId);
    }

    private void insertRecord(String taskId, String reason) {
        jdbc.update("""
                INSERT INTO task_message_retry_record (
                    task_id, requested_by, reason, previous_retry_count, create_time
                ) VALUES (?, 'learning-user', ?, 5, TIMESTAMP '2026-01-01 09:30:00')
                """, taskId, reason);
    }
}
