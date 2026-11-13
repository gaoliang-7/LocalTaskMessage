package com.example.localtaskmessage.taskmessage.retry;

import com.example.localtaskmessage.taskmessage.config.TaskMessageProperties;
import com.example.localtaskmessage.taskmessage.core.TaskMessageSender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 按分片找出到期任务，补发首发遗漏、发送失败和租约过期的消息。
 */
public class TaskMessageRetryJob {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageRetryJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final TaskMessageSender sender;

    public TaskMessageRetryJob(JdbcTemplate jdbcTemplate, TaskMessageSender sender) {
        this.jdbcTemplate = jdbcTemplate;
        this.sender = sender;
    }

    /**
     * 扫描一个补偿组并逐条尝试发送，某条失败不影响本轮其他任务。
     */
    public void retryGroup(TaskMessageProperties.RetryGroup group) {
        long started = System.nanoTime();

        log.debug(
                "补偿组开始，group={}，houseNumber={}",
                group.name(),
                group.houseNumber()
        );

        try {
            // retry_ready_at 是生成列：待发送取重试时间，发送中取租约到期时间。
            // SENT 和 FAILED 对应 NULL，不参与扫描；每轮重新查，避免漏掉重新到期的任务。
            List<String> taskIds = jdbcTemplate.queryForList("""
                            SELECT task_id
                            FROM local_task_message
                            WHERE house_number = ?
                              AND retry_ready_at <= CURRENT_TIMESTAMP(3)
                            ORDER BY retry_ready_at, id
                            LIMIT ?
                            """,
                    String.class,
                    group.houseNumber(),
                    group.batchSize()
            );

            if (taskIds.isEmpty()) return;

            log.info(
                    "补偿扫描命中，group={}，houseNumber={}，taskCount={}",
                    group.name(),
                    group.houseNumber(),
                    taskIds.size()
            );

            // 扫描到的只是候选，最终能否发送仍由 sender 的原子抢占决定。
            for (String taskId : taskIds) {
                try {
                    sender.send(taskId);
                } catch (Exception failure) {
                    log.error(
                            "补偿发送异常，group={}，houseNumber={}，taskId={}",
                            group.name(),
                            group.houseNumber(),
                            taskId,
                            failure
                    );
                }
            }

        } catch (Exception failure) {
            log.error(
                    "补偿组执行异常，group={}，houseNumber={}",
                    group.name(),
                    group.houseNumber(),
                    failure
            );
        } finally {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started
            );

            log.debug(
                    "补偿组结束，group={}，houseNumber={}，elapsedMillis={}",
                    group.name(),
                    group.houseNumber(),
                    elapsedMillis
            );
        }

    }

}
