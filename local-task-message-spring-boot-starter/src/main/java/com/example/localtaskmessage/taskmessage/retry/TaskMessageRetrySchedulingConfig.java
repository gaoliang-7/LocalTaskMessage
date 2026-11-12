package com.example.localtaskmessage.taskmessage.retry;

import com.example.localtaskmessage.taskmessage.config.TaskMessageProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;

@Configuration(proxyBeanMethods = false)
public class TaskMessageRetrySchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageRetrySchedulingConfig.class);

    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(name = "taskMessageRetryScheduler")
    public ThreadPoolTaskScheduler taskMessageRetryScheduler(TaskMessageProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

        int poolSize = properties.retryGroups().size();

        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("task-retry-");
        scheduler.setRemoveOnCancelPolicy(true);

        log.info("初始化补偿调度器，poolSize={}", poolSize);

        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean(name = "taskMessageRetrySchedulingConfigurer")
    public SmartInitializingSingleton taskMessageRetrySchedulingConfigurer(
            TaskMessageProperties properties,
            TaskMessageRetryJob retryJob,
            @Qualifier("taskMessageRetryScheduler")
            ThreadPoolTaskScheduler scheduler
    ) {
        return () -> {
            for (TaskMessageProperties.RetryGroup group : properties.retryGroups()) {
                scheduler.scheduleWithFixedDelay(
                        () -> retryJob.retryGroup(group),
                        Instant.now().plusSeconds(5),
                        Duration.ofSeconds(group.fixedDelaySeconds())
                );

                log.info(
                        "注册补偿组，group={}，houseNumber={}，"
                                + "batchSize={}，fixedDelaySeconds={}",
                        group.name(),
                        group.houseNumber(),
                        group.batchSize(),
                        group.fixedDelaySeconds()
                );

            }
        };
    }
}
