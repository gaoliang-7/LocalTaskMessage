package com.example.localtaskmessage.order.messaging;

import com.example.localtaskmessage.order.model.OrderCreatedMessage;
import com.example.localtaskmessage.order.service.OrderCreatedConsumeService;
import com.example.localtaskmessage.taskmessage.transport.rocketmq.RocketMqClientFactory;

import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把订单创建 Topic、消费组和消息监听器接到一起。
 */
@Configuration(proxyBeanMethods = false)
public class OrderMqConfig {

    /**
     * 启动订单消息消费者，应用关闭时释放连接。
     */
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
