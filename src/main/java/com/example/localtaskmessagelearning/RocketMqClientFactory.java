package com.example.localtaskmessagelearning;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

//@Configuration
//@EnableAsync
//@EnableScheduling
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


//    private static final Logger log =
//            LoggerFactory.getLogger(RocketMqClientFactory.class);
//
//    @Bean
//    public ClientServiceProvider clientServiceProvider() {
//        return ClientServiceProvider.loadService();
//    }
//
//    @Bean
//    public ClientConfiguration  rocketMqClientConfiguration(
//            @Value("${rocketmq.endpoints}") String endpoints) {
//
//        return ClientConfiguration.newBuilder()
//                .setEndpoints(endpoints)
//                .enableSsl(false)
//                .setRequestTimeout(Duration.ofSeconds(5))
//                .build();
//    }
//
//    @Bean
//    public Producer rocketMqProducer(ClientServiceProvider provider,
//                                     ClientConfiguration configuration) throws ClientException {
//        return provider.newProducerBuilder()
//                .setClientConfiguration(configuration)
//                .setTopics("order_created")
//                .build();
//    }
//
//    @Bean
//    public PushConsumer orderCreatedPushConsumer(ClientServiceProvider provider,
//                                                 ClientConfiguration clientConfiguration,
//                                                 OrderCreatedMessageListener listener) throws ClientException {
//        FilterExpression filter = new FilterExpression("*", FilterExpressionType.TAG);
//
//        return provider.newPushConsumerBuilder()
//                .setClientConfiguration(clientConfiguration)
//                .setConsumerGroup(OrderCreatedConsumeService.GROUP_NAME)
//                .setSubscriptionExpressions(Map.of("order_created", filter))
//                .setMessageListener(listener)
//                .build();
//    }
//
////    @Bean(defaultCandidate = false)
////    public ThreadPoolTaskExecutor taskMessageSendExecutor() {
////        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
////        executor.setCorePoolSize(1);
////        executor.setMaxPoolSize(1);
////        executor.setQueueCapacity(1);
////        executor.setThreadNamePrefix("task-send-");
////        return executor;
////    }
//
//    @Bean(defaultCandidate = false)
//    public ThreadPoolTaskExecutor taskMessageSendExecutor(
//            TaskMessageProperties properties
//    ) {
//        TaskMessageProperties.SendExecutor settings = properties.sendExecutor();
//
//        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
//        executor.setCorePoolSize(settings.corePoolSize());
//        executor.setMaxPoolSize(settings.maxPoolSize());
//        executor.setQueueCapacity(settings.queueCapacity());
//        executor.setThreadNamePrefix("task-send-");
//
//        RejectedExecutionHandler rejectionHandler =
//                switch (settings.rejectionPolicy()) {
//                    case DEFER_TO_RETRY ->
//                        new ThreadPoolExecutor.AbortPolicy();
//
//                    case CALLER_RUNS -> (task, pool) -> {
//                        if (pool.isShutdown()) {
//                            throw new RejectedExecutionException(
//                                    "首发线程池已关闭"
//                            );
//                        }
//                        log.warn(
//                                "首发线程池已满，提交线程执行，thread={}",
//                                Thread.currentThread().getName()
//                        );
//
//                        task.run();
//                    };
//
//                };
//
//        executor.setRejectedExecutionHandler(rejectionHandler);
//
//        log.info(
//                "首发线程池配置，corePoolSize={}，maxPoolSize={}，"
//                        + "queueCapacity={}，rejectionPolicy={}",
//                settings.corePoolSize(),
//                settings.maxPoolSize(),
//                settings.queueCapacity(),
//                settings.rejectionPolicy()
//        );
//
//        return executor;
//    }

}
