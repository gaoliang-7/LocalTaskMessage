package com.example.localtaskmessage.order.service;

import com.example.localtaskmessage.order.model.CreateOrderRequest;
import com.example.localtaskmessage.order.model.OrderCreatedMessage;
import com.example.localtaskmessage.taskmessage.api.LocalTaskMessage;
import com.example.localtaskmessage.taskmessage.api.TaskMessageCommand;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * 保存订单，并把要发出的订单创建消息交给消息切面。
 */
@Service
public class OrderService {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public OrderService(
            JdbcTemplate jdbcTemplate,
            JsonMapper jsonMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 在一个事务里完成下单和消息登记，提交成功后再发送 MQ。
     */
    @Transactional
    @LocalTaskMessage
    public TaskMessageCommand createOrder(
            CreateOrderRequest request
    ) {
        // taskId 同时写进任务表和消息正文，重发时复用，消费端据此去重。
        String taskId = UUID.randomUUID().toString();

        String payload = jsonMapper.writeValueAsString(
                Map.of(
                        "taskId", taskId,
                        "orderNo", request.orderNo(),
                        "userId", request.userId(),
                        "payAmount", request.payAmount()
                )
        );

        jdbcTemplate.update("""
                INSERT INTO biz_order (
                    order_no, user_id, pay_amount
                )
                VALUES (?, ?, ?)
                """,
                request.orderNo(),
                request.userId(),
                request.payAmount()
        );

        // 方法返回后，切面会在事务提交前登记这条消息；登记失败，订单也会回滚。
        return new TaskMessageCommand(
                taskId,
                request.orderNo(),
                OrderCreatedMessage.TOPIC,
                payload
        );
    }
}
