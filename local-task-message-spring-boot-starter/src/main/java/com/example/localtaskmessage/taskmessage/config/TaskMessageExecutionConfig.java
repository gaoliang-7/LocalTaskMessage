package com.example.localtaskmessage.taskmessage.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 配置首次发送消息的专用线程池，以及线程池满时的处理方式。
 */
@Configuration(proxyBeanMethods = false)
public class TaskMessageExecutionConfig {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TaskMessageExecutionConfig.class
            );

    /**
     * 创建首发线程池，宿主也可以提供同名 Bean 替换它。
     */
    // 不作为其他异步任务的默认线程池，消息监听器通过名称单独注入。
    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(name = "taskMessageSendExecutor")
    public ThreadPoolTaskExecutor taskMessageSendExecutor(
            TaskMessageProperties properties
    ) {
        TaskMessageProperties.SendExecutor settings =
                properties.sendExecutor();

        ThreadPoolTaskExecutor executor =
                new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(settings.corePoolSize());
        executor.setMaxPoolSize(settings.maxPoolSize());
        executor.setQueueCapacity(settings.queueCapacity());
        executor.setThreadNamePrefix("task-send-");

        RejectedExecutionHandler rejectionHandler =
                switch (settings.rejectionPolicy()) {
                    // 抛出拒绝异常后，由提交后的事件监听器记录日志，任务留给补偿。
                    case DEFER_TO_RETRY -> new ThreadPoolExecutor.AbortPolicy();

                    case CALLER_RUNS -> (task, pool) -> {
                        // 关闭时明确拒绝，让任务留给补偿，避免静默丢掉本次首发。
                        if (pool.isShutdown()) {
                            throw new RejectedExecutionException(
                                    "首发线程池已关闭"
                            );
                        }

                        log.warn(
                                "首发线程池已满，提交线程执行，thread={}",
                                Thread.currentThread().getName()
                        );

                        task.run();
                    };
                };

        executor.setRejectedExecutionHandler(
                rejectionHandler
        );

        log.info(
                "首发线程池配置，corePoolSize={}，maxPoolSize={}，"
                        + "queueCapacity={}，rejectionPolicy={}",
                settings.corePoolSize(),
                settings.maxPoolSize(),
                settings.queueCapacity(),
                settings.rejectionPolicy()
        );

        return executor;
    }
}
