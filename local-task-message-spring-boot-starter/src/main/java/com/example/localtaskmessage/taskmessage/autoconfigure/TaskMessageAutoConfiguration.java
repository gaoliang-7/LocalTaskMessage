package com.example.localtaskmessage.taskmessage.autoconfigure;

import com.example.localtaskmessage.taskmessage.config.TaskMessageExecutionConfig;
import com.example.localtaskmessage.taskmessage.config.TaskMessageProperties;
import com.example.localtaskmessage.taskmessage.core.TaskMessageRegistrar;
import com.example.localtaskmessage.taskmessage.core.TaskMessageSender;
import com.example.localtaskmessage.taskmessage.retry.TaskMessageRetryJob;
import com.example.localtaskmessage.taskmessage.retry.TaskMessageRetrySchedulingConfig;
import com.example.localtaskmessage.taskmessage.spring.LocalTaskMessageAspect;
import com.example.localtaskmessage.taskmessage.spring.TaskMessageCreatedListener;
import com.example.localtaskmessage.taskmessage.transport.TaskMessageTransport;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * 复用宿主的数据源和事务管理器，创建消息登记、发送和补偿所需的组件。
 */
@AutoConfiguration(
        after = {
                JdbcTemplateAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                RocketMqTaskMessageAutoConfiguration.class
        },
        before = TransactionAutoConfiguration.class
)
@ConditionalOnClass({JdbcTemplate.class, Aspect.class})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class, TaskMessageTransport.class})
@EnableConfigurationProperties(TaskMessageProperties.class)
// 事务先于消息切面执行，让业务写入和消息登记落在同一个事务里。
@EnableTransactionManagement(order = 0, proxyTargetClass = true)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@Import({TaskMessageExecutionConfig.class, TaskMessageRetrySchedulingConfig.class})
public class TaskMessageAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageAutoConfiguration.class);

    /**
     * 用宿主的 JdbcTemplate 创建登记器，跟业务写入共用数据源。
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskMessageRegistrar taskMessageRegistrar(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher publisher
    ) {
        log.info("装配本地消息组件，dataSource={}，transactionManager={}",
                jdbcTemplate.getDataSource().getClass().getName(),
                transactionManager.getClass().getName());
        return new TaskMessageRegistrar(jdbcTemplate, publisher);
    }

    /**
     * 创建首发和补偿共用的发送器，统一处理抢占和结果回写。
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskMessageSender taskMessageSender(
            JdbcTemplate jdbcTemplate,
            TaskMessageTransport transport,
            TaskMessageProperties properties
    ) {
        return new TaskMessageSender(jdbcTemplate, transport, properties);
    }

    /**
     * 创建扫描到期任务的补偿入口。
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskMessageRetryJob taskMessageRetryJob(JdbcTemplate jdbcTemplate, TaskMessageSender sender) {
        return new TaskMessageRetryJob(jdbcTemplate, sender);
    }

    /**
     * 让 @LocalTaskMessage 方法在返回后自动登记消息。
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalTaskMessageAspect localTaskMessageAspect(TaskMessageRegistrar registrar) {
        return new LocalTaskMessageAspect(registrar);
    }

    /**
     * 把事务提交后的消息事件接到首发线程池。
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskMessageCreatedListener taskMessageCreatedListener(
            TaskMessageSender sender,
            @Qualifier("taskMessageSendExecutor") ThreadPoolTaskExecutor executor
    ) {
        return new TaskMessageCreatedListener(sender, executor);
    }
}
