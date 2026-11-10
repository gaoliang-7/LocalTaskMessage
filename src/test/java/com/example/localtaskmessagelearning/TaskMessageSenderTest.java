package com.example.localtaskmessagelearning;

import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskMessageSenderTest {

    private static final String TASK_ID = "TASK-SENDER-TEST";
    private static final String PAYLOAD = "{\"taskId\":\"TASK-SENDER-TEST\",\"orderNo\":\"ORDER-TEST\"}";

    private JdbcTemplate jdbc;
    private Producer producer;
    private ClientServiceProvider provider;
    private SendReceipt receipt;
    private TaskMessageSender sender;

    @BeforeEach
    void setUp() {
        // 每次测试独享内存数据库，实际执行发送类中的 SQL。
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:sender-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE local_task_message (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(64) NOT NULL UNIQUE,
                    biz_id VARCHAR(64) NOT NULL,
                    topic VARCHAR(128) NOT NULL,
                    payload VARCHAR(8192) NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_error VARCHAR(512),
                    lock_token VARCHAR(64),
                    locked_until TIMESTAMP(3),
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

        producer = mock(Producer.class);
        receipt = mock(SendReceipt.class);
        // 只使用 SDK 的本地消息构造器，不创建真实 MQ 连接。
        provider = ClientServiceProvider.loadService();
        sender = new TaskMessageSender(jdbc, producer, provider,
                new TaskMessageProperties(5, 10, 60, 30));
    }

    @AfterEach
    void closeDatabase() {
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void shouldSendStoredMessageAndMarkSent() throws Exception {
        insertTask(TASK_ID);
        jdbc.update("UPDATE local_task_message SET retry_count = 2, last_error = 'OLD_ERROR' WHERE task_id = ?", TASK_ID);
        when(producer.send(any(Message.class))).thenReturn(receipt);

        sender.send(TASK_ID);

        var message = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(message.capture());
        assertThat(message.getValue().getTopic()).isEqualTo("order_created");
        assertThat(message.getValue().getKeys()).containsExactly(TASK_ID);
        assertThat(StandardCharsets.UTF_8.decode(message.getValue().getBody()).toString())
                .isEqualTo(PAYLOAD);
        assertThat(field(TASK_ID, "status")).isEqualTo("SENT");
        assertThat(field(TASK_ID, "retry_count")).isEqualTo("2");
        assertThat(field(TASK_ID, "last_error")).isNull();
        assertLeaseReleased();
    }

    @ParameterizedTest(name = "已失败{0}次，本次失败后等待{1}秒")
    @CsvSource({"0, 10", "1, 20", "2, 40", "3, 60"})
    void shouldIncreaseBackoffAndCapAtMaximum(int previousFailures, int delaySeconds) throws Exception {
        insertTask(TASK_ID);
        jdbc.update("UPDATE local_task_message SET retry_count = ? WHERE task_id = ?", previousFailures, TASK_ID);
        var failure = new IllegalStateException("SIMULATED_MQ_FAILURE");
        when(producer.send(any(Message.class))).thenThrow(failure);

        assertThatThrownBy(() -> sender.send(TASK_ID)).isSameAs(failure);

        assertThat(field(TASK_ID, "status")).isEqualTo("PENDING");
        assertThat(field(TASK_ID, "retry_count")).isEqualTo(Integer.toString(previousFailures + 1));
        assertThat(field(TASK_ID, "last_error")).contains("SIMULATED_MQ_FAILURE");
        assertThat(recordedDelaySeconds()).isEqualTo(delaySeconds);
        assertLeaseReleased();
        verify(producer).send(any(Message.class));
    }

    @Test
    void shouldUseConfiguredLeaseAndFailurePolicy() throws Exception {
        insertTask(TASK_ID);
        sender = new TaskMessageSender(jdbc, producer, provider,
                new TaskMessageProperties(3, 2, 3, 7));
        var failure = new IllegalStateException("CUSTOM_POLICY_FAILURE");
        when(producer.send(any(Message.class))).thenAnswer(invocation -> {
            long leaseSeconds = jdbc.queryForObject("""
                    SELECT TIMESTAMPDIFF(SECOND, CURRENT_TIMESTAMP(3), locked_until)
                    FROM local_task_message WHERE task_id = ?
                    """, Long.class, TASK_ID);
            assertThat(leaseSeconds).isBetween(5L, 7L);
            throw failure;
        });

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThatThrownBy(() -> sender.send(TASK_ID)).isSameAs(failure);
            assertThat(field(TASK_ID, "retry_count")).isEqualTo(Integer.toString(attempt));
            assertLeaseReleased();
            if (attempt < 3) {
                assertThat(field(TASK_ID, "status")).isEqualTo("PENDING");
                assertThat(recordedDelaySeconds()).isEqualTo(attempt == 1 ? 2 : 3);
                makeTaskDue();
            }
        }

        assertThat(field(TASK_ID, "status")).isEqualTo("FAILED");
        sender.send(TASK_ID);
        new TaskMessageRetryJob(jdbc, sender).retryPending();
        verify(producer, times(3)).send(any(Message.class));
    }

    @Test
    void shouldRecoverAfterDueRetry() throws Exception {
        insertTask(TASK_ID);
        var failure = new IllegalStateException("TEMPORARY_MQ_FAILURE");
        when(producer.send(any(Message.class))).thenThrow(failure).thenReturn(receipt);
        assertThatThrownBy(() -> sender.send(TASK_ID)).isSameAs(failure);

        // 冷却期内，直接发送和定时任务都不能再次调用 SDK。
        var job = new TaskMessageRetryJob(jdbc, sender);
        sender.send(TASK_ID);
        job.retryPending();
        verify(producer).send(any(Message.class));

        // 只将测试任务设为到期，无需真的等待十秒。
        makeTaskDue();
        job.retryPending();

        assertThat(field(TASK_ID, "status")).isEqualTo("SENT");
        assertThat(field(TASK_ID, "retry_count")).isEqualTo("1");
        assertThat(field(TASK_ID, "last_error")).isNull();
        assertLeaseReleased();
        verify(producer, times(2)).send(any(Message.class));
    }

    @Test
    void shouldSkipUnclaimableTasks() throws Exception {
        for (String state : new String[]{"SENT", "FAILED", "PENDING", "PROCESSING"}) {
            insertTask(state);
            jdbc.update("""
                    UPDATE local_task_message
                    SET status = ?,
                        next_retry_time = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP),
                        lock_token = 'existing-owner',
                        locked_until = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP)
                    WHERE task_id = ?
                    """, state, state);
            sender.send(state);
        }
        sender.send("MISSING-TASK");
        verifyNoInteractions(producer);
    }

    @Test
    void shouldReclaimExpiredLease() throws Exception {
        insertTask(TASK_ID);
        jdbc.update("""
                UPDATE local_task_message
                SET status = 'PROCESSING', lock_token = 'crashed-owner',
                    locked_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP)
                WHERE task_id = ?
                """, TASK_ID);
        when(producer.send(any(Message.class))).thenReturn(receipt);

        new TaskMessageRetryJob(jdbc, sender).retryPending();

        assertThat(field(TASK_ID, "status")).isEqualTo("SENT");
        assertLeaseReleased();
        verify(producer).send(any(Message.class));
    }

    @ParameterizedTest(name = "旧发送结果失败={0}，均不能覆盖新令牌")
    @ValueSource(booleans = {false, true})
    void shouldNotOverwriteNewOwnerAfterLeaseChanges(boolean sendFails) throws Exception {
        insertTask(TASK_ID);
        when(producer.send(any(Message.class))).thenAnswer(invocation -> {
            // 模拟发送尚未返回时，任务已换成新的持有者。
            jdbc.update("""
                    UPDATE local_task_message
                    SET lock_token = 'new-owner',
                        locked_until = TIMESTAMPADD(SECOND, 30, CURRENT_TIMESTAMP)
                    WHERE task_id = ?
                    """, TASK_ID);
            if (sendFails) {
                throw new IllegalStateException("OLD_SENDER_FAILURE");
            }
            return receipt;
        });

        assertThatThrownBy(() -> sender.send(TASK_ID)).isInstanceOf(IllegalStateException.class);

        assertThat(field(TASK_ID, "status")).isEqualTo("PROCESSING");
        assertThat(field(TASK_ID, "lock_token")).isEqualTo("new-owner");
        assertThat(field(TASK_ID, "locked_until")).isNotNull();
        assertThat(field(TASK_ID, "retry_count")).isEqualTo("0");
        assertThat(field(TASK_ID, "last_error")).isNull();
        verify(producer).send(any(Message.class));
    }

    @Test
    void shouldFailOnFirstAttemptWhenLimitIsOne() throws Exception {
        insertTask(TASK_ID);
        sender = new TaskMessageSender(jdbc, producer, provider,
                new TaskMessageProperties(1, 10, 60, 30));
        var failure = new IllegalStateException("FIRST_FAILURE");
        when(producer.send(any(Message.class))).thenThrow(failure);

        assertThatThrownBy(() -> sender.send(TASK_ID)).isSameAs(failure);
        assertThat(field(TASK_ID, "status")).isEqualTo("FAILED");
        assertThat(field(TASK_ID, "retry_count")).isEqualTo("1");
        assertLeaseReleased();
        sender.send(TASK_ID);
        verify(producer).send(any(Message.class));
    }

    private void insertTask(String taskId) {
        jdbc.update("""
                INSERT INTO local_task_message (task_id, biz_id, topic, payload, next_retry_time)
                VALUES (?, 'ORDER-TEST', 'order_created', ?, TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP))
                """, taskId, PAYLOAD);
    }

    private void makeTaskDue() {
        jdbc.update("""
                UPDATE local_task_message
                SET next_retry_time = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP)
                WHERE task_id = ?
                """, TASK_ID);
    }

    private String field(String taskId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM local_task_message WHERE task_id = ?",
                String.class, taskId);
    }

    private long recordedDelaySeconds() {
        // 比较同一次 UPDATE 写入的两个时间，避免测试机器运行速度影响断言。
        return jdbc.queryForObject("""
                SELECT next_retry_time, update_time
                FROM local_task_message WHERE task_id = ?
                """, (rs, rowNum) -> Duration.between(
                rs.getObject("update_time", LocalDateTime.class),
                rs.getObject("next_retry_time", LocalDateTime.class)
        ).getSeconds(), TASK_ID);
    }

    private void assertLeaseReleased() {
        assertThat(field(TASK_ID, "lock_token")).isNull();
        assertThat(field(TASK_ID, "locked_until")).isNull();
    }
}
