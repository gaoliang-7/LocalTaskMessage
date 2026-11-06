package com.example.localtaskmessagelearning;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableAsync
@EnableScheduling
public class RocketMqConfig {

    @Bean
    public ClientServiceProvider clientServiceProvider() {
        return ClientServiceProvider.loadService();
    }

    @Bean
    public ClientConfiguration  rocketMqClientConfiguration(
            @Value("${rocketmq.endpoints}") String endpoints) {

        return ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .enableSsl(false)
                .setRequestTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Bean
    public Producer rocketMqProducer(ClientServiceProvider provider,
                                     ClientConfiguration configuration) throws ClientException {
        return provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics("order_created")
                .build();
    }

    @Bean
    public PushConsumer orderCreatedPushConsumer(ClientServiceProvider provider,
                                                 ClientConfiguration clientConfiguration,
                                                 OrderCreatedMessageListener listener) throws ClientException {
        FilterExpression filter = new FilterExpression("*", FilterExpressionType.TAG);

        return provider.newPushConsumerBuilder()
                .setClientConfiguration(clientConfiguration)
                .setConsumerGroup(OrderCreatedConsumeService.GROUP_NAME)
                .setSubscriptionExpressions(Map.of("order_created", filter))
                .setMessageListener(listener)
                .build();
    }

}
