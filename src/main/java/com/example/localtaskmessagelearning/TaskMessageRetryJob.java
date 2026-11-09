package com.example.localtaskmessagelearning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TaskMessageRetryJob {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageRetryJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final TaskMessageSender sender;

    public TaskMessageRetryJob(JdbcTemplate jdbcTemplate, TaskMessageSender sender) {
        this.jdbcTemplate = jdbcTemplate;
        this.sender = sender;
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 5000)
    public void retryPending() {
        List<String> taskIds = jdbcTemplate.queryForList("""
                SELECT task_id
                FROM local_task_message
                WHERE (
                    status = 'PENDING'
                    AND next_retry_time <= CURRENT_TIMESTAMP(3)
                )
                OR (
                    status = 'PROCESSING'
                    AND locked_until <= CURRENT_TIMESTAMP(3)
                )
                ORDER BY id
                LIMIT 20
                """, String.class);

        if (taskIds.isEmpty()) return;

        log.info("本轮补偿任务数={}", taskIds.size());

        for (String taskId : taskIds) {
            try {
                sender.send(taskId);
            } catch (Exception e) {
                log.error("补偿发送异常，taskId={}", taskId, e);

            }
        }

    }

}
