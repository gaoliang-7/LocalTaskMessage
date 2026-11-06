package com.example.localtaskmessagelearning;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.time.Duration;

@Configuration
@EnableAsync
public class RocketMqConfig {

    @Bean
    public ClientServiceProvider clientServiceProvider() {
        return ClientServiceProvider.loadService();
    }

    @Bean(destroyMethod = "close")
    public Producer rocketMqProducer(
            ClientServiceProvider provider,
            @Value("${rocketmq.endpoints}") String endpoints) throws ClientException {

        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .enableSsl(false)
                .setRequestTimeout(Duration.ofSeconds(5))
                .build();

        return provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics("order_created")
                .build();
    }

}
