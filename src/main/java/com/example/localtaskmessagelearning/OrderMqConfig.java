package com.example.localtaskmessagelearning;

import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OrderMqConfig {

    @Bean(destroyMethod = "close")
    public Producer rocketMqProducer(
            RocketMqClientFactory clientFactory
    ) throws ClientException {
        return clientFactory.createProducer(
                OrderCreatedMessage.TOPIC
        );
    }

    @Bean(destroyMethod = "close")
    public PushConsumer orderCreatedPushConsumer(
            RocketMqClientFactory clientFactory,
            OrderCreatedMessageListener listener
    ) throws ClientException {
        return clientFactory.createPushConsumer(
                OrderCreatedMessage.TOPIC,
                OrderCreatedConsumeService.GROUP_NAME,
                listener
        );
    }
}