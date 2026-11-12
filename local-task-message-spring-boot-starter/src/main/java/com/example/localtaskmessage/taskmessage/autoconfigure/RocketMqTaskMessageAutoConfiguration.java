package com.example.localtaskmessage.taskmessage.autoconfigure;

import com.example.localtaskmessage.taskmessage.transport.TaskMessageTransport;
import com.example.localtaskmessage.taskmessage.transport.rocketmq.RocketMqClientFactory;
import com.example.localtaskmessage.taskmessage.config.RocketMqProperties;
import com.example.localtaskmessage.taskmessage.transport.rocketmq.RocketMqTaskMessageTransport;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = TaskMessageAutoConfiguration.class)
@ConditionalOnClass(Producer.class)
@ConditionalOnSingleCandidate(DataSource.class)
@EnableConfigurationProperties(RocketMqProperties.class)
public class RocketMqTaskMessageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RocketMqClientFactory rocketMqClientFactory(RocketMqProperties properties) {
        return new RocketMqClientFactory(properties.endpoints());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "rocketMqProducer")
    public Producer rocketMqProducer(RocketMqClientFactory factory, RocketMqProperties properties)
            throws ClientException {
        return factory.createProducer(properties.topics().toArray(String[]::new));
    }

    @Bean
    @ConditionalOnMissingBean(TaskMessageTransport.class)
    public TaskMessageTransport taskMessageTransport(
            RocketMqClientFactory factory,
            @Qualifier("rocketMqProducer") Producer producer
    ) {
        return new RocketMqTaskMessageTransport(factory, producer);
    }
}
