package com.example.localtaskmessagelearning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskMessageAdminTest {

    private static final String VALID_BODY =
            "{\"requestedBy\":\"learning-user\",\"reason\":\"MQ recovered\"}";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TaskMessageAdminService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        // 从容器取得事务代理，确保测试真正经过 @Transactional。
        service = context.getBean(TaskMessageAdminService.class);
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
    void shouldListLatestTwentyFailedTasks() throws Exception {
        for (int index = 0; index < 22; index++) {
            insertTask("FAILED-" + index, "FAILED");
        }
        insertTask("PENDING-TASK", "PENDING");
        insertTask("SENT-TASK", "SENT");

        var response = mvc.perform(get("/task-messages/failed"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        var tasks = jsonMapper.readTree(response.getContentAsString());
        assertThat(tasks.size()).isEqualTo(20);
        assertThat(tasks.get(0).get("taskId").asString()).isEqualTo("FAILED-21");
        assertThat(tasks.get(19).get("taskId").asString()).isEqualTo("FAILED-2");
        assertThat(tasks.get(0).get("bizId").asString()).isEqualTo("ORDER-FAILED-21");
        assertThat(tasks.get(0).has("payload")).isFalse();
    }

    @Test
    void shouldAcceptRetryAndKeepPreviousFailureInAudit() throws Exception {
        insertTask("ACCEPT", "FAILED");
        Map<String, Object> before = taskRow("ACCEPT");

        var response = mvc.perform(post("/task-messages/ACCEPT/retry")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andReturn().getResponse();

        var result = jsonMapper.readTree(response.getContentAsString());
        assertThat(result.get("taskId").asString()).isEqualTo("ACCEPT");
        assertThat(result.get("result").asString()).isEqualTo("ACCEPTED");
        assertThat(field("ACCEPT", "status")).isEqualTo("PENDING");
        assertThat(field("ACCEPT", "retry_count")).isEqualTo("0");
        assertThat(field("ACCEPT", "last_error")).isNull();
        assertThat(field("ACCEPT", "lock_token")).isNull();
        assertThat(field("ACCEPT", "locked_until")).isNull();
        assertThat(field("ACCEPT", "payload")).isEqualTo(before.get("PAYLOAD"));
        assertThat(field("ACCEPT", "biz_id")).isEqualTo(before.get("BIZ_ID"));
        assertThat(field("ACCEPT", "topic")).isEqualTo(before.get("TOPIC"));
        assertThat(recordCount("ACCEPT")).isEqualTo(1);
        var audit = jdbc.queryForMap("SELECT * FROM task_message_retry_record WHERE task_id = ?", "ACCEPT");
        assertThat(audit.get("REQUESTED_BY")).isEqualTo("learning-user");
        assertThat(audit.get("REASON")).isEqualTo("MQ recovered");
        assertThat(audit.get("PREVIOUS_RETRY_COUNT")).isEqualTo(5);
        assertThat(audit.get("PREVIOUS_ERROR")).isEqualTo("OLD_FAILURE");
    }

    @ParameterizedTest(name = "状态为{0}时拒绝人工补发")
    @ValueSource(strings = {"PENDING", "PROCESSING", "SENT"})
    void shouldRejectNonFailedTasks(String state) throws Exception {
        insertTask("CONFLICT", state);
        Map<String, Object> before = taskRow("CONFLICT");

        mvc.perform(post("/task-messages/CONFLICT/retry")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict());

        assertThat(taskRow("CONFLICT")).isEqualTo(before);
        assertThat(recordCount("CONFLICT")).isZero();
    }

    @Test
    void shouldReturn404ForMissingTask() throws Exception {
        mvc.perform(post("/task-messages/MISSING/retry")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());
        assertThat(recordCount("MISSING")).isZero();
    }

    @ParameterizedTest(name = "拒绝非法补发请求 [{index}]")
    @MethodSource("invalidRetryBodies")
    void shouldRejectInvalidRequestWithoutChangingTask(String body) throws Exception {
        insertTask("INVALID", "FAILED");
        Map<String, Object> before = taskRow("INVALID");

        mvc.perform(post("/task-messages/INVALID/retry")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertThat(taskRow("INVALID")).isEqualTo(before);
        assertThat(recordCount("INVALID")).isZero();
    }

    static Stream<String> invalidRetryBodies() {
        return Stream.of(
                "{\"reason\":\"test\"}",
                "{\"requestedBy\":\" \",\"reason\":\"test\"}",
                "{\"requestedBy\":\"" + "x".repeat(65) + "\",\"reason\":\"test\"}",
                "{\"requestedBy\":\"user\"}",
                "{\"requestedBy\":\"user\",\"reason\":\" \"}",
                "{\"requestedBy\":\"user\",\"reason\":\"" + "x".repeat(257) + "\"}"
        );
    }

    @Test
    void shouldRollBackResetWhenAuditInsertFails() {
        insertTask("ROLLBACK", "FAILED");
        Map<String, Object> before = taskRow("ROLLBACK");
        // 仅给当前测试数据库加约束，让真实的记录 INSERT 失败。
        jdbc.execute("""
                ALTER TABLE task_message_retry_record
                ADD CONSTRAINT reject_test_audit
                CHECK (reason <> 'SIMULATED_AUDIT_FAILURE')
                """);

        assertThatThrownBy(() -> service.retry("ROLLBACK",
                new RetryTaskRequest("learning-user", "SIMULATED_AUDIT_FAILURE")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 在事务方法退出后重新查库，状态、次数、旧错误等全部应恢复。
        assertThat(taskRow("ROLLBACK")).isEqualTo(before);
        assertThat(recordCount("ROLLBACK")).isZero();
    }

    @Test
    void shouldAcceptOnlyOneConcurrentRetry() throws Exception {
        insertTask("RACE", "FAILED");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> request = () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(post("/task-messages/RACE/retry")
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(new int[]{first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(202, 409);
            assertThat(field("RACE", "status")).isEqualTo("PENDING");
            assertThat(field("RACE", "retry_count")).isEqualTo("0");
            assertThat(recordCount("RACE")).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldAppendAuditForEachNewFailedRound() {
        insertTask("HISTORY", "FAILED");
        service.retry("HISTORY", new RetryTaskRequest("first-user", "first recovery"));
        jdbc.update("""
                UPDATE local_task_message
                SET status = 'FAILED', retry_count = 3, last_error = 'SECOND_FAILURE'
                WHERE task_id = ?
                """, "HISTORY");

        service.retry("HISTORY", new RetryTaskRequest("second-user", "second recovery"));

        assertThat(recordCount("HISTORY")).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT previous_error FROM task_message_retry_record
                WHERE task_id = ? ORDER BY id
                """, String.class, "HISTORY")).containsExactly("OLD_FAILURE", "SECOND_FAILURE");
        assertThat(jdbc.queryForList("""
                SELECT previous_retry_count FROM task_message_retry_record
                WHERE task_id = ? ORDER BY id
                """, Integer.class, "HISTORY")).containsExactly(5, 3);
        assertThat(field("HISTORY", "status")).isEqualTo("PENDING");
    }

    private void insertTask(String taskId, String state) {
        jdbc.update("""
                INSERT INTO local_task_message (
                    task_id, biz_id, topic, payload, status, retry_count, last_error
                )
                VALUES (?, ?, 'order_created', ?, ?, ?, 'OLD_FAILURE')
                """, taskId, "ORDER-" + taskId,
                "{\"taskId\":\"" + taskId + "\"}", state, "FAILED".equals(state) ? 5 : 2);
    }

    private Map<String, Object> taskRow(String taskId) {
        return jdbc.queryForMap("SELECT * FROM local_task_message WHERE task_id = ?", taskId);
    }

    private String field(String taskId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM local_task_message WHERE task_id = ?",
                String.class, taskId);
    }

    private long recordCount(String taskId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM task_message_retry_record WHERE task_id = ?",
                Long.class, taskId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({TaskMessageAdminService.class, TaskMessageAdminController.class})
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            var source = new DriverManagerDataSource();
            source.setDriverClassName("org.h2.Driver");
            source.setUrl("jdbc:h2:mem:admin-" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            source.setUsername("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE local_task_message (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        task_id VARCHAR(64) NOT NULL UNIQUE,
                        biz_id VARCHAR(64) NOT NULL,
                        topic VARCHAR(128) NOT NULL,
                        payload VARCHAR(8192) NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        retry_count INT NOT NULL,
                        next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_error VARCHAR(512),
                        lock_token VARCHAR(64),
                        locked_until TIMESTAMP(3),
                        update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE task_message_retry_record (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        task_id VARCHAR(64) NOT NULL,
                        requested_by VARCHAR(64) NOT NULL,
                        reason VARCHAR(256) NOT NULL,
                        previous_retry_count INT NOT NULL,
                        previous_error VARCHAR(512),
                        create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            return jdbc;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }
}
