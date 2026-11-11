package com.example.localtaskmessagelearning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class TaskMessageRetrySchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageRetrySchedulingConfig.class);

    @Bean(defaultCandidate = false)
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
    public SchedulingConfigurer taskMessageRetrySchedulingConfigurer(
            TaskMessageProperties properties,
            TaskMessageRetryJob retryJob,
            @Qualifier("taskMessageRetryScheduler")
            ThreadPoolTaskScheduler scheduler
    ) {
        return registrar -> {
            registrar.setTaskScheduler(scheduler);

            for (TaskMessageProperties.RetryGroup group : properties.retryGroups()) {
                registrar.addFixedDelayTask(
                        new FixedDelayTask(
                                () -> retryJob.retryGroup(group),
                                Duration.ofSeconds(
                                  group.fixedDelaySeconds()
                                ),
                                Duration.ofSeconds(5)
                        )
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
