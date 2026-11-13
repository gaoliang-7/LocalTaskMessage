package com.example.localtaskmessage.order.service;

import com.example.localtaskmessage.order.model.OrderCreatedMessage;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处理订单创建消息，用消费记录防止同一任务被重复统计。
 */
@Service
public class OrderCreatedConsumeService {

    /** 本业务使用的消费组，也是消费去重记录和统计记录的分组标识。 */
    public static final String GROUP_NAME = "order_created_consumer";

    private final JdbcTemplate jdbcTemplate;

    public OrderCreatedConsumeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 首次处理时记录消费并增加统计，返回 true；重复消息直接返回 false。
     */
    @Transactional
    public boolean consume(OrderCreatedMessage event) {
        // (consumer_group, task_id) 是联合主键，并发重复消费也只有一条能插入成功。
        try {
            jdbcTemplate.update("""
                    INSERT INTO message_consume_record (
                        consumer_group, task_id, order_no
                    )
                    VALUES (?, ?, ?)
                    """, GROUP_NAME, event.taskId(), event.orderNo());
        } catch (DuplicateKeyException duplicate) {
            // 已经处理过，正常返回，让监听器确认这条重复消息。
            return false;
        }

        // 去重记录和业务处理必须同事务；统计失败时回滚记录，下次投递还能重新处理。
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
