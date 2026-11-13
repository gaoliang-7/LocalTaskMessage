package com.example.localtaskmessage.taskmessage.core;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把消息任务存进当前业务事务，保证业务和消息要么都保存，要么都回滚。
 */
public class TaskMessageRegistrar {
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher publisher;

    public TaskMessageRegistrar(JdbcTemplate jdbcTemplate, ApplicationEventPublisher publisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.publisher = publisher;
    }

    /**
     * 登记待发送任务；必须已有事务，不能单独提交一条消息任务。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void register(String taskId, String bizId, String topic, String payload) {
        jdbcTemplate.update("""
                INSERT INTO local_task_message (
                    task_id, biz_id, topic, payload, next_retry_time
                )
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, taskId, bizId, topic, payload);

        // 这里先发布事件；AFTER_COMMIT 监听器只在事务提交成功后安排首发。
        publisher.publishEvent(new TaskMessageCreatedEvent(taskId));
    }
}
