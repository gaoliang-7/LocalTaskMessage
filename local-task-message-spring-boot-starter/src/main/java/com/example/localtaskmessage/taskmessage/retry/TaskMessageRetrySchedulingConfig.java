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

/**
 * 给每个补偿组安排独立的定时任务，各组按自己的间隔运行。
 */
@Configuration(proxyBeanMethods = false)
public class TaskMessageRetrySchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageRetrySchedulingConfig.class);

    /**
     * 每个补偿组准备一个调度线程，避免一个组发送慢时拖住其他组。
     */
    // 只供消息补偿使用，不替换宿主其他定时任务的默认调度器。
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

    /**
     * 等单例 Bean 初始化完成后，按配置逐组注册补偿任务。
     */
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
                // 首轮等 5 秒；后续从上轮结束时计时，同一组不会因为执行慢而叠跑。
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
