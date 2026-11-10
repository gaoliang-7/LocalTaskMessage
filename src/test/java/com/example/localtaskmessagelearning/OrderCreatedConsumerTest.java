package com.example.localtaskmessagelearning;

import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderCreatedConsumerTest {

    private static final String GROUP = OrderCreatedConsumeService.GROUP_NAME;

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private JsonMapper jsonMapper;
    private OrderCreatedConsumeService service;
    private OrderCreatedMessageListener listener;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        jsonMapper = context.getBean(JsonMapper.class);
        // 通过事务代理调用实际业务服务，监听器本身也使用实际实现。
        service = context.getBean(OrderCreatedConsumeService.class);
        listener = context.getBean(OrderCreatedMessageListener.class);
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
    void shouldCommitFirstMessageAndIgnoreExtraFields() {
        ConsumeResult result = listener.consume(message("TASK-FIRST", "ORDER-FIRST"));

        assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(markerCount("TASK-FIRST")).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT order_no FROM message_consume_record
                WHERE consumer_group = ? AND task_id = ?
                """, String.class, GROUP, "TASK-FIRST")).isEqualTo("ORDER-FIRST");
    }

    @Test
    void shouldAcknowledgeDuplicateWithoutRepeatingBusiness() {
        assertThat(listener.consume(message("TASK-DUPLICATE", "ORDER-DUPLICATE")))
                .isEqualTo(ConsumeResult.SUCCESS);
        assertThat(listener.consume(message("TASK-DUPLICATE", "ORDER-DUPLICATE")))
                .isEqualTo(ConsumeResult.SUCCESS);

        assertThat(markerCount("TASK-DUPLICATE")).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(1);
    }

    @Test
    void shouldTreatDifferentTaskIdsAsDifferentMessages() {
        assertThat(listener.consume(message("TASK-A", "ORDER-SHARED")))
                .isEqualTo(ConsumeResult.SUCCESS);
        assertThat(listener.consume(message("TASK-B", "ORDER-SHARED")))
                .isEqualTo(ConsumeResult.SUCCESS);

        assertThat(markerCount("TASK-A")).isEqualTo(1);
        assertThat(markerCount("TASK-B")).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(2);
    }

    @Test
    void shouldScopeDeduplicationByConsumerGroup() {
        jdbc.update("""
                INSERT INTO message_consume_record (consumer_group, task_id, order_no)
                VALUES ('other-consumer-group', 'TASK-SHARED', 'ORDER-SHARED')
                """);

        assertThat(listener.consume(message("TASK-SHARED", "ORDER-SHARED")))
                .isEqualTo(ConsumeResult.SUCCESS);

        assertThat(markerCount("TASK-SHARED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_consume_record", Long.class))
                .isEqualTo(2);
        assertThat(businessCount()).isEqualTo(1);
    }

    @Test
    void shouldApplyConcurrentDuplicatesOnce() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ConsumeResult> consume = () -> {
                MessageView message = message("TASK-CONCURRENT", "ORDER-CONCURRENT");
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return listener.consume(message);
            };
            var first = executor.submit(consume);
            var second = executor.submit(consume);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(ConsumeResult.SUCCESS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(ConsumeResult.SUCCESS);
            assertThat(markerCount("TASK-CONCURRENT")).isEqualTo(1);
            assertThat(businessCount()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRollBackConsumeMarkerAndRetryAfterRecovery() {
        assertThat(listener.consume(message("TASK-PREVIOUS", "ORDER-PREVIOUS")))
                .isEqualTo(ConsumeResult.SUCCESS);
        jdbc.update("UPDATE order_consume_stat SET consumer_group = ? WHERE consumer_group = ?",
                GROUP + "_unavailable", GROUP);

        MessageView retryable = message("TASK-RECOVERY", "ORDER-RECOVERY");
        assertThat(listener.consume(retryable)).isEqualTo(ConsumeResult.FAILURE);
        assertThat(markerCount("TASK-RECOVERY")).isZero();
        assertThat(markerCount("TASK-PREVIOUS")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT processed_count FROM order_consume_stat WHERE consumer_group = ?",
                Long.class, GROUP + "_unavailable")).isEqualTo(1);

        jdbc.update("UPDATE order_consume_stat SET consumer_group = ? WHERE consumer_group = ?",
                GROUP, GROUP + "_unavailable");
        assertThat(listener.consume(retryable)).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(markerCount("TASK-RECOVERY")).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(2);
        assertThat(listener.consume(retryable)).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(businessCount()).isEqualTo(2);
    }

    @Test
    void shouldNotSwallowDuplicateKeyFromBusinessUpdate() {
        // 只在当前测试数据库制造业务 UPDATE 的唯一键冲突。
        jdbc.update("INSERT INTO order_consume_stat (consumer_group, processed_count) VALUES ('other-stat', 1)");
        jdbc.execute("""
                ALTER TABLE order_consume_stat
                ADD CONSTRAINT unique_test_processed_count UNIQUE (processed_count)
                """);

        var event = new OrderCreatedMessage("TASK-BUSINESS-CONFLICT", "ORDER-BUSINESS-CONFLICT");
        assertThatThrownBy(() -> service.consume(event)).isInstanceOf(DuplicateKeyException.class);
        assertThat(markerCount(event.taskId())).isZero();
        assertThat(businessCount()).isZero();
        assertThat(listener.consume(message(event.taskId(), event.orderNo())))
                .isEqualTo(ConsumeResult.FAILURE);
        assertThat(markerCount(event.taskId())).isZero();

        jdbc.update("DELETE FROM order_consume_stat WHERE consumer_group = 'other-stat'");
        assertThat(listener.consume(message(event.taskId(), event.orderNo())))
                .isEqualTo(ConsumeResult.SUCCESS);
        assertThat(markerCount(event.taskId())).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "拒绝非法订单消息：{0}")
    @MethodSource("invalidMessages")
    void shouldRejectInvalidFieldsWithoutWrites(String scenario, String payload) {
        assertThat(listener.consume(body(payload))).isEqualTo(ConsumeResult.FAILURE);
        assertNoBusinessWrites();
    }

    static Stream<Arguments> invalidMessages() {
        return Stream.of(
                Arguments.of("缺少taskId", "{\"orderNo\":\"ORDER-INVALID\"}"),
                Arguments.of("taskId为空白", "{\"taskId\":\" \",\"orderNo\":\"ORDER-INVALID\"}"),
                Arguments.of("taskId超过64字符", "{\"taskId\":\"" + "T".repeat(65) + "\",\"orderNo\":\"ORDER-INVALID\"}"),
                Arguments.of("缺少orderNo", "{\"taskId\":\"TASK-INVALID\"}"),
                Arguments.of("orderNo为空白", "{\"taskId\":\"TASK-INVALID\",\"orderNo\":\" \"}"),
                Arguments.of("orderNo超过64字符", "{\"taskId\":\"TASK-INVALID\",\"orderNo\":\"" + "O".repeat(65) + "\"}")
        );
    }

    @ParameterizedTest(name = "拒绝不可解析的消息 [{index}]")
    @ValueSource(strings = {"not-json", "", "null", "[]"})
    void shouldRejectMalformedPayloadWithoutWrites(String payload) {
        assertThat(listener.consume(body(payload))).isEqualTo(ConsumeResult.FAILURE);
        assertNoBusinessWrites();
    }

    @ParameterizedTest(name = "接受taskId长度{0}和orderNo长度{1}")
    @CsvSource({"1, 1", "64, 64"})
    void shouldAcceptValidIdentifierBoundaries(int taskLength, int orderLength) {
        String taskId = "T".repeat(taskLength);
        assertThat(listener.consume(message(taskId, "O".repeat(orderLength))))
                .isEqualTo(ConsumeResult.SUCCESS);
        assertThat(markerCount(taskId)).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(1);
    }

    private MessageView message(String taskId, String orderNo) {
        return body(jsonMapper.writeValueAsString(Map.of(
                "taskId", taskId,
                "orderNo", orderNo,
                "userId", 1001,
                "payAmount", 88.00
        )));
    }

    private MessageView body(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        MessageView message = mock(MessageView.class);
        // UTF-8解码会推进Buffer位置；重复投递时每次返回新的视图。
        when(message.getBody()).thenAnswer(invocation -> ByteBuffer.wrap(bytes));
        return message;
    }

    private long markerCount(String taskId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM message_consume_record
                WHERE consumer_group = ? AND task_id = ?
                """, Long.class, GROUP, taskId);
    }

    private long businessCount() {
        return jdbc.queryForObject("SELECT processed_count FROM order_consume_stat WHERE consumer_group = ?",
                Long.class, GROUP);
    }

    private void assertNoBusinessWrites() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_consume_record", Long.class)).isZero();
        assertThat(businessCount()).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({OrderCreatedConsumeService.class, OrderCreatedMessageListener.class})
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            var source = new DriverManagerDataSource();
            source.setDriverClassName("org.h2.Driver");
            source.setUrl("jdbc:h2:mem:consumer-" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            source.setUsername("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE message_consume_record (
                        consumer_group VARCHAR(64) NOT NULL,
                        task_id VARCHAR(64) NOT NULL,
                        order_no VARCHAR(64) NOT NULL,
                        create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (consumer_group, task_id)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE order_consume_stat (
                        consumer_group VARCHAR(64) PRIMARY KEY,
                        processed_count BIGINT NOT NULL DEFAULT 0,
                        update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            jdbc.update("INSERT INTO order_consume_stat (consumer_group) VALUES (?)", GROUP);
            return jdbc;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }
}
