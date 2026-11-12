package com.example.localtaskmessage.mqdemo;

import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public class MqConsumerDemo {

    public static void main(String[] args) throws Exception {
        ClientServiceProvider provider = ClientServiceProvider.loadService();

        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints("127.0.0.1:8081")
                .enableSsl(false)
                .setRequestTimeout(Duration.ofSeconds(5))
                .build();

        FilterExpression filter = new FilterExpression("*", FilterExpressionType.TAG);

        try (PushConsumer consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup("order_created_consumer")
                .setSubscriptionExpressions(Map.of("order_created", filter))
                .setMessageListener(message -> {
                    String body = StandardCharsets.UTF_8.decode(message.getBody()).toString();

                    System.out.printf(
                            "收到消息，messageId=%s，keys=%s，body=%s%n",
                            message.getMessageId(), message.getKeys(), body
                    );

                    return ConsumeResult.SUCCESS;
                })
                .build()) {

            System.out.println("消费者已启动，消费组=" + consumer.getConsumerGroup() + "，按回车退出");
            System.in.read();

        }
    }

}
