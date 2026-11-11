package com.example.localtaskmessagelearning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

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

    @Transactional
    @LocalTaskMessage
    public TaskMessageCommand createOrder(
            CreateOrderRequest request
    ) {
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

        return new TaskMessageCommand(
                taskId,
                request.orderNo(),
                OrderCreatedMessage.TOPIC,
                payload
        );
    }
}