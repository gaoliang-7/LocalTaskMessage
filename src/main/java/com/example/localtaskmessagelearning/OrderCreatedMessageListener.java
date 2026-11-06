package com.example.localtaskmessagelearning;

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

    @Override
    public ConsumeResult consume(MessageView message) {
        try {
            String body = StandardCharsets.UTF_8.decode(message.getBody()).toString();
            OrderCreatedMessage event = jsonMapper.readValue(body, OrderCreatedMessage.class);

            var violations = validator.validate(event);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException(violations);
            }

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
