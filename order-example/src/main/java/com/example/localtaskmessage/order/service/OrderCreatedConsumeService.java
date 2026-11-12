package com.example.localtaskmessage.order.service;

import com.example.localtaskmessage.order.model.OrderCreatedMessage;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCreatedConsumeService {

    public static final String GROUP_NAME = "order_created_consumer";

    private final JdbcTemplate jdbcTemplate;

    public OrderCreatedConsumeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public boolean consume(OrderCreatedMessage event) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO message_consume_record (
                        consumer_group, task_id, order_no
                    )
                    VALUES (?, ?, ?)
                    """, GROUP_NAME, event.taskId(), event.orderNo());
        } catch (DuplicateKeyException duplicate) {
            return false;
        }

        int updated = jdbcTemplate.update("""
                UPDATE order_consume_stat
                SET processed_count = processed_count + 1,
                    update_time = CURRENT_TIMESTAMP
                WHERE consumer_group = ?
                """, GROUP_NAME);

        if (updated != 1) {
            throw new IllegalStateException("消费统计初始化记录不存在，group=" + GROUP_NAME);
        }

        return true;
    }

}
