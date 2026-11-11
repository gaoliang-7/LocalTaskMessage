package com.example.localtaskmessagelearning;

import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RocketMqTaskMessageTransport implements TaskMessageTransport{

    private final Logger log = LoggerFactory.getLogger(RocketMqTaskMessageTransport.class);

    private final RocketMqClientFactory clientFactory;
    private final Producer producer;

    public RocketMqTaskMessageTransport(RocketMqClientFactory clientFactory,
                                        @Qualifier("rocketMqProducer")
                                        Producer producer) {
        this.clientFactory = clientFactory;
        this.producer = producer;
    }

    @Override
    public SendResult send(String taskId, String topic, String payload) throws ClientException {
        Message message = clientFactory.createMessage(
                taskId,
                topic,
                payload
        );

        SendReceipt receipt = producer.send(message);

        String messageId = receipt.getMessageId().toString();

        log.info(
                "Broker 已确认，taskId={}，topic={}，messageId={}",
                taskId,
                topic,
                messageId
        );

        return new SendResult(messageId);
    }
}
