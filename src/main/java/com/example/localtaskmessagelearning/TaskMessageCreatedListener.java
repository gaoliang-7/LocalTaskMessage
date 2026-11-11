package com.example.localtaskmessagelearning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TaskMessageCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageCreatedListener.class);

    private final TaskMessageSender sender;
    private final ThreadPoolTaskExecutor executor;

    public TaskMessageCreatedListener(TaskMessageSender sender,
                                      @Qualifier("taskMessageSendExecutor")
                                      ThreadPoolTaskExecutor executor) {
        this.sender = sender;
        this.executor = executor;
    }


    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(TaskMessageCreatedEvent event) {
        try {
//            sender.send(event.taskId());
            executor.execute(() -> send(event.taskId()));
        } catch (TaskRejectedException rejected) {
//            log.error("消息发送处理异常，taskId={}", event.taskId(), e);
            log.warn(
                    "首发未入队，交由补偿，taskId={}，"
                            + "activeCount={}，queueSize={}",
                    event.taskId(),
                    executor.getActiveCount(),
                    executor.getQueueSize()
            );
        }
    }

    private void send(String taskId) {
        try {
            log.info(
                    "开始首发，taskId={}，thread={}",
                    taskId,
                    Thread.currentThread().getName()
            );

            sender.send(taskId);
        } catch (Exception failure) {
            log.error(
                    "首发执行异常，taskId={}",
                    taskId,
                    failure
            );
        }
    }

}
