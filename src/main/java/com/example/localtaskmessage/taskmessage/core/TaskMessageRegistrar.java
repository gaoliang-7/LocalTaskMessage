package com.example.localtaskmessage.taskmessage.core;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskMessageRegistrar {
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher publisher;

    public TaskMessageRegistrar(JdbcTemplate jdbcTemplate, ApplicationEventPublisher publisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.publisher = publisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void register(String taskId, String bizId, String topic, String payload) {
        jdbcTemplate.update("""
                INSERT INTO local_task_message (
                    task_id, biz_id, topic, payload, next_retry_time
                )
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, taskId, bizId, topic, payload);

        publisher.publishEvent(new TaskMessageCreatedEvent(taskId));
    }
}
