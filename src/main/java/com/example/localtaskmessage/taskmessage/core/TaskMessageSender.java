package com.example.localtaskmessage.taskmessage.core;

import com.example.localtaskmessage.taskmessage.config.TaskMessageProperties;
import com.example.localtaskmessage.taskmessage.transport.TaskMessageTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TaskMessageSender {

    private static final Logger log =
            LoggerFactory.getLogger(TaskMessageSender.class);

    private final JdbcTemplate jdbcTemplate;
    private final TaskMessageTransport transport;
    private final TaskMessageProperties properties;

    public TaskMessageSender(
            JdbcTemplate jdbcTemplate,
            TaskMessageTransport transport,
            TaskMessageProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transport = transport;
        this.properties = properties;

        log.info("消息任务配置：{}", properties);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void send(String taskId) throws Exception {
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

        if (claimed != 1) {
            return;
        }

        log.info(
                "抢占任务成功，taskId={}，token={}",
                taskId,
                token
        );

        TaskMessageTransport.SendResult receipt;

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
                log.warn(
                        "租约已失效，跳过本次发送，taskId={}",
                        taskId
                );
                return;
            }

            PendingMessage task = tasks.get(0);

            receipt = transport.send(
                    taskId,
                    task.topic(),
                    task.payload()
            );
        } catch (Exception failure) {
            try {
                int recorded = jdbcTemplate.update("""
                        UPDATE local_task_message
                        SET status = CASE
                                WHEN retry_count + 1 >= ?
                                    THEN 'FAILED'
                                ELSE 'PENDING'
                            END,
                            next_retry_time = CASE
                                WHEN retry_count + 1 >= ?
                                    THEN next_retry_time
                                ELSE TIMESTAMPADD(
                                    SECOND,
                                    LEAST(
                                        ?,
                                        ? * POWER(2, retry_count)
                                    ),
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
                    log.warn(
                            "租约已失效，本次失败不回写，taskId={}",
                            taskId
                    );
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
                    """,
                    taskId,
                    token
            );
        } catch (DataAccessException failure) {
            throw new IllegalStateException(
                    "MQ 已确认，SENT 回写异常，taskId=" + taskId
                            + "，messageId=" + receipt.messageId(),
                    failure
            );
        }

        if (updated != 1) {
            throw new IllegalStateException(
                    "MQ 已确认，SENT 回写未命中，taskId=" + taskId
                            + "，messageId=" + receipt.messageId()
            );
        }

        log.info(
                "消息已发送并标记SENT，taskId={}，messageId={}",
                taskId,
                receipt.messageId()
        );
    }

    private record PendingMessage(
            String topic,
            String payload
    ) {
    }

}
