package com.example.localtaskmessage.taskmessage.transport.rocketmq;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Component
public class RocketMqClientFactory {
    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;

    public RocketMqClientFactory(
            @Value("${rocketmq.endpoints}") String endpoints
    ) {
        this.provider = ClientServiceProvider.loadService();
        this.configuration = ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .enableSsl(false)
                .setRequestTimeout(Duration.ofSeconds(5))
                .build();
    }

    public Producer createProducer(String... topics) throws ClientException {
        return provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(topics)
                .build();
    }

    public PushConsumer createPushConsumer(
            String topic,
            String consumerGroup,
            MessageListener listener
    ) throws ClientException {
        FilterExpression filter = new FilterExpression(
                "*",
                FilterExpressionType.TAG
        );

        return provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(consumerGroup)
                .setSubscriptionExpressions(Map.of(topic, filter))
                .setMessageListener(listener)
                .build();
    }

    public Message createMessage(
            String taskId,
            String topic,
            String payload
    ) {
        return provider.newMessageBuilder()
                .setTopic(topic)
                .setKeys(taskId)
                .setBody(
                        payload.getBytes(StandardCharsets.UTF_8)
                )
                .build();
    }

}
