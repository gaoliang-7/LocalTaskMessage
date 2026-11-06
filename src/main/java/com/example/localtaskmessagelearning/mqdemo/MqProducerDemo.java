package com.example.localtaskmessagelearning.mqdemo;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public class MqProducerDemo {

    public static void main(String[] args) throws Exception{
        ClientServiceProvider provider = ClientServiceProvider.loadService();

        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints("127.0.0.1:8081")
                .enableSsl(false)
                .setRequestTimeout(Duration.ofSeconds(5))
                .build();

        try (Producer producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics("order_created")
                .build()){

            String taskId = UUID.randomUUID().toString();

            String body = """
                    {"taskId":"%s","orderNo":"ORDER-MQ-DEMO-001"}
                    """.formatted(taskId);

            Message message = provider.newMessageBuilder()
                    .setTopic("order_created")
                    .setKeys(taskId)
                    .setBody(body.getBytes(StandardCharsets.UTF_8))
                    .build();

            SendReceipt receipt = producer.send(message);

            System.out.printf(
                    "发送成功，taskId=%s，messageId=%s%n",
                    taskId, receipt.getMessageId()
            );

        }
    }

}
