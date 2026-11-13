package com.example.localtaskmessage.taskmessage.core;

import com.example.localtaskmessage.taskmessage.config.TaskMessageProperties;
import com.example.localtaskmessage.taskmessage.transport.TaskMessageTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 首发和补偿共用的发送流程：先抢任务，发给 MQ，再回写结果。
 */
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

    /**
     * 尝试发送一个到期任务，没抢到就退出；失败时安排下次重试或标记 FAILED。
     * MQ 已接收但回写失败时，保留租约等补偿，消费端需要按 taskId 去重。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void send(String taskId) throws Exception {
        // 挂起调用方事务；提交回调里的 CALLER_RUNS 也不能沿用原事务资源。
        String token = UUID.randomUUID().toString();

        // 用一条 UPDATE 抢占到期任务，避免首发、补偿和多个实例同时发送。
        // token 标识本次抢占；后续读写都核对 token 和租约，防止旧线程覆盖新结果。
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
            // 抢到后再确认租约仍有效，过期或被接管就跳过本次发送。
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
                // 失败后的等待时间从初始值开始，每次翻倍但不超过最大间隔；次数达到上限则停止重试。
                // MySQL 按 SET 顺序赋值，retry_count 最后再加一，前面的计算才能用旧次数。
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
                // 保留最初的发送异常，同时带上回写异常，方便一起排查。
                failure.addSuppressed(recordFailure);
            }

            throw failure;
        }

        // 到这里 MQ 已确认接收，回写异常不能再按“发送失败”处理。
        // 保留 PROCESSING 和原租约，过期后补发可能产生重复消息，由消费端去重。
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

    /**
     * 抢到任务后，发送给 MQ 所需的内容。
     *
     * @param topic 目标 Topic
     * @param payload 数据库中保存的消息正文
     */
    private record PendingMessage(
            String topic,
            String payload
    ) {
    }

}
