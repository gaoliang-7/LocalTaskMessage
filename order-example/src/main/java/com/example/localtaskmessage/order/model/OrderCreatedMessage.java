package com.example.localtaskmessage.order.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 消费端处理订单创建消息时需要的字段，正文中的其他字段会被忽略。
 *
 * @param taskId 消息任务 ID，同一任务重发时不变，用来防止重复处理
 * @param orderNo 这条消息关联的订单号
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreatedMessage(
        @NotBlank
        @Size(max = 64)
        String taskId,

        @NotBlank
        @Size(max = 64)
        String orderNo
) {

    /** 订单创建消息发送和订阅的 Topic。 */
    public static final String TOPIC = "order_created";
}
