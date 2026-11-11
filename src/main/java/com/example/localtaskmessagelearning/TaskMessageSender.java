package com.example.localtaskmessagelearning;

import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class TaskMessageSender {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageSender.class);

    private final JdbcTemplate jdbcTemplate;
    private final Producer producer;
    private final ClientServiceProvider provider;
    private final TaskMessageProperties properties;

    public TaskMessageSender(JdbcTemplate jdbcTemplate,
                             Producer producer,
                             ClientServiceProvider provider,
                             TaskMessageProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.producer = producer;
        this.provider = provider;
        this.properties = properties;

        log.info("消息任务配置：{}", properties);
    }

//    public void send(String taskId) throws ClientException {
//        List<PendingMessage> tasks = jdbcTemplate.query("""
//                SELECT topic, payload
//                FROM local_task_message
//                WHERE task_id = ? AND status = 'PENDING'
//                """,
//                (rs, rowNum) -> new PendingMessage(
//                        rs.getString("topic"), rs.getString("payload")),
//                taskId
//        );
//
//        if (tasks.isEmpty()) return;
//
//        PendingMessage task = tasks.get(0);
//
//        Message message = provider.newMessageBuilder()
//                .setTopic(task.topic())
//                .setKeys(taskId)
//                .setBody(task.payload().getBytes(StandardCharsets.UTF_8))
//                .build();
//
//        SendReceipt receipt = producer.send(message);
//
//        int updated = jdbcTemplate.update("""
//                UPDATE local_task_message
//                SET status = 'SENT', last_error = NULL,
//                    update_time = CURRENT_TIMESTAMP
//                WHERE task_id = ? AND status = 'PENDING'
//                """, taskId);
//
//        if (updated != 1) {
//            throw new IllegalStateException("消息已发送，但任务状态更新异常，taskId=" + taskId);
//        }
//
//        log.info("消息已发送并标记SENT，taskId={}，messageId={}",
//                taskId, receipt.getMessageId());
//    }

//    public synchronized void send(String taskId) throws ClientException{
//
//        List<PendingMessage> tasks = jdbcTemplate.query("""
//            SELECT topic, payload
//            FROM local_task_message
//            WHERE task_id = ? AND status = 'PENDING'
//              AND next_retry_time <= CURRENT_TIMESTAMP
//            """,
//                (rs, rowNum) -> new PendingMessage(
//                        rs.getString("topic"), rs.getString("payload")),
//                taskId
//        );
//
//        if (tasks.isEmpty()) {
//            return;
//        }
//
//        PendingMessage task = tasks.get(0);
//
//        try {
//            Message message = provider.newMessageBuilder()
//                    .setTopic(task.topic())
//                    .setKeys(taskId)
//                    .setBody(task.payload().getBytes(StandardCharsets.UTF_8))
//                    .build();
//
//            SendReceipt receipt = producer.send(message);
//
//            int updated = jdbcTemplate.update("""
//                UPDATE local_task_message
//                SET status = 'SENT', last_error = NULL,
//                    update_time = CURRENT_TIMESTAMP
//                WHERE task_id = ? AND status = 'PENDING'
//                """, taskId);
//
//            if (updated != 1) {
//                throw new IllegalStateException("消息已发送，但任务状态更新异常，taskId=" + taskId);
//            }
//
//            log.info("消息已发送并标记SENT，taskId={}，messageId={}",
//                    taskId, receipt.getMessageId());
//
//        } catch (Exception failure) {
//            try {
//                jdbcTemplate.update("""
//                    UPDATE local_task_message
//                    SET retry_count = retry_count + 1,
//                        next_retry_time = TIMESTAMPADD(SECOND, 10, CURRENT_TIMESTAMP),
//                        last_error = LEFT(?, 512),
//                        update_time = CURRENT_TIMESTAMP
//                    WHERE task_id = ? AND status = 'PENDING'
//                    """, failure.toString(), taskId);
//            } catch (Exception recordFailure) {
//                failure.addSuppressed(recordFailure);
//            }
//
//            throw failure;
//        }
//
//    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void send(String taskId) throws ClientException {
        String token = UUID.randomUUID().toString();

        int claimed = jdbcTemplate.update("""
                        UPDATE local_task_message
                        SET status = 'PROCESSING',
                            lock_token = ?,
                            locked_until = TIMESTAMPADD(
                                SECOND, ?, CURRENT_TIMESTAMP(3)
                            ),
                            update_time = CURRENT_TIMESTAMP
                        WHERE task_id = ?
                          AND (
                            (
                                status = 'PENDING'
                                AND next_retry_time <= CURRENT_TIMESTAMP(3)
                            )
                            OR
                            (
                                status = 'PROCESSING'
                                AND locked_until <= CURRENT_TIMESTAMP(3)
                            )
                          )
                        """,
                token,
                properties.leaseSeconds(),
                taskId
        );

        if (claimed != 1) return;

        log.info("抢占任务成功，taskId={}，token={}", taskId, token);

        SendReceipt receipt;

        // 固定30s租约，发送经常超过租期时加入续期
        try {
            List<PendingMessage> tasks = jdbcTemplate.query("""
                            SELECT topic, payload
                            FROM local_task_message
                            WHERE task_id = ?
                              AND status = 'PROCESSING'
                              AND lock_token = ?
                              AND locked_until > CURRENT_TIMESTAMP(3)
                            """,
                    (rs, rowNum) -> new PendingMessage(
                            rs.getString("topic"),
                            rs.getString("payload")
                    ),
                    taskId,
                    token
            );

            if (tasks.isEmpty()) {
                log.warn("租约已失效，跳过本次发送，taskId={}", taskId);
                return;
            }

            PendingMessage task = tasks.get(0);
            Message message = provider.newMessageBuilder()
                    .setTopic(task.topic())
                    .setKeys(taskId)
                    .setBody(task.payload().getBytes(StandardCharsets.UTF_8))
                    .build();

            receipt = producer.send(message);

        } catch (Exception failure) {
            try {
                int recorded = jdbcTemplate.update("""
                                UPDATE local_task_message
                                SET status = CASE
                                        WHEN retry_count + 1 >= ? THEN 'FAILED'
                                        ELSE 'PENDING'
                                    END,
                                    next_retry_time = CASE
                                        WHEN retry_count + 1 >= ? THEN next_retry_time
                                        ELSE TIMESTAMPADD(
                                            SECOND,
                                            LEAST(?, ? * POWER(2, retry_count)),
                                            CURRENT_TIMESTAMP
                                        )
                                    END,
                                    last_error = LEFT(?, 512),
                                    lock_token = NULL,
                                    locked_until = NULL,
                                    update_time = CURRENT_TIMESTAMP,
                                    retry_count = retry_count + 1
                                WHERE task_id = ?
                                  AND status = 'PROCESSING'
                                  AND lock_token = ?
                                  AND locked_until > CURRENT_TIMESTAMP(3)
                                """,
                        properties.maxFailures(),
                        properties.maxFailures(),
                        properties.maxDelaySeconds(),
                        properties.initialDelaySeconds(),
                        failure.toString(),
                        taskId,
                        token
                );

                if (recorded == 0) {
                    log.warn("租约已失效，本次失败不回写，taskId={}", taskId);
                }

            } catch (Exception recordFailure) {
                failure.addSuppressed(recordFailure);
            }

            throw failure;
        }

        int updated;
        try {
            updated = jdbcTemplate.update("""
                    UPDATE local_task_message
                    SET status = 'SENT',
                        lock_token = NULL,
                        locked_until = NULL,
                        last_error = NULL,
                        update_time = CURRENT_TIMESTAMP
                    WHERE task_id = ?
                      AND status = 'PROCESSING'
                      AND lock_token = ?
                      AND locked_until > CURRENT_TIMESTAMP(3)
                    """, taskId, token);
        } catch (DataAccessException failure) {
            throw new IllegalStateException(
                    "MQ 已确认，SENT 回写异常，taskId=" + taskId
                            + "，messageId=" + receipt.getMessageId(),
                    failure
            );
        }

        if (updated != 1) {
            throw new IllegalStateException(
                    "MQ 已确认，SENT 回写未命中，taskId=" + taskId
            );
        }

        log.info("消息已发送并标记SENT，taskId={}，messageId={}",
                taskId, receipt.getMessageId());
    }

    private record PendingMessage(String topic, String payload) {
    }

}
