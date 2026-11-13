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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 统一创建 RocketMQ 生产者、消费者和消息，复用同一套连接配置。
 */
public class RocketMqClientFactory {
    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;

    /**
     * 连接指定 Proxy，当前关闭 SSL，单次请求超时设为 5 秒。
     */
    public RocketMqClientFactory(
            String endpoints
    ) {
        this.provider = ClientServiceProvider.loadService();
        this.configuration = ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .enableSsl(false)
                .setRequestTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 创建可向指定 Topic 发送消息的生产者。
     */
    public Producer createProducer(String... topics) throws ClientException {
        return provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(topics)
                .build();
    }

    /**
     * 创建指定消费组的消费者，订阅这个 Topic 的全部 Tag。
     */
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

    /**
     * 把 JSON 正文按 UTF-8 编码，并用 taskId 作为消息 Key，方便查询。
     */
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
