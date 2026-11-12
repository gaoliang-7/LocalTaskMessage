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

@Configuration(proxyBeanMethods = false)
public class TaskMessageExecutionConfig {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TaskMessageExecutionConfig.class
            );

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
                    case DEFER_TO_RETRY -> new ThreadPoolExecutor.AbortPolicy();

                    case CALLER_RUNS -> (task, pool) -> {
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
