package com.example.localtaskmessage.order.messaging;

import com.example.localtaskmessage.order.model.OrderCreatedMessage;
import com.example.localtaskmessage.order.service.OrderCreatedConsumeService;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * 接收订单创建消息，校验正文后交给消费业务，再告诉 MQ 是否处理成功。
 */
@Component
public class OrderCreatedMessageListener implements MessageListener {

    public static final Logger log = LoggerFactory.getLogger(OrderCreatedMessageListener.class);

    private final JsonMapper jsonMapper;
    private final Validator validator;
    private final OrderCreatedConsumeService consumeService;

    public OrderCreatedMessageListener(JsonMapper jsonMapper,
                                       Validator validator,
                                       OrderCreatedConsumeService consumeService) {
        this.jsonMapper = jsonMapper;
        this.validator = validator;
        this.consumeService = consumeService;
    }

    /**
     * 正常处理和重复消息都确认成功；解析、校验或业务异常时交给 MQ 重投。
     */
    @Override
    public ConsumeResult consume(MessageView message) {
        try {
            String body = StandardCharsets.UTF_8.decode(message.getBody()).toString();
            OrderCreatedMessage event = jsonMapper.readValue(body, OrderCreatedMessage.class);

            // MQ 消息不经过 Controller 的 @Valid，反序列化后需要主动校验。
            var violations = validator.validate(event);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException(violations);
            }

            // 通过独立 Service 的 Spring 代理调用，保证消费去重和统计的事务生效。
            boolean firstProcessing = consumeService.consume(event);

            log.info("消费完成，taskId={}，messageId={}，首次处理={}",
                    event.taskId(), message.getMessageId(), firstProcessing);

            return ConsumeResult.SUCCESS;

        } catch (Exception e) {
            log.error("订单消息消费失败，messageId={}", message.getMessageId(), e);
            return ConsumeResult.FAILURE;
        }
    }

}
