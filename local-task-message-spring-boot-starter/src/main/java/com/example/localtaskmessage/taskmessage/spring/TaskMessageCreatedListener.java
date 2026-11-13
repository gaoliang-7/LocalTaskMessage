package com.example.localtaskmessage.taskmessage.spring;

import com.example.localtaskmessage.taskmessage.core.TaskMessageCreatedEvent;
import com.example.localtaskmessage.taskmessage.core.TaskMessageSender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事务提交后，把新消息交给首发线程池；没能发出的任务由定时补偿接手。
 */
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

    /**
     * 等订单和任务都提交成功后，再尝试把发送工作放进线程池。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(TaskMessageCreatedEvent event) {
        try {
            executor.execute(() -> send(event.taskId()));
        } catch (TaskRejectedException rejected) {
            // 入队失败时任务仍是 PENDING，不增加发送失败次数，留给补偿扫描。
            log.warn(
                    "首发未入队，交由补偿，taskId={}，"
                            + "activeCount={}，queueSize={}",
                    event.taskId(),
                    executor.getActiveCount(),
                    executor.getQueueSize()
            );
        }
    }

    /**
     * 执行一次首发并记录异常，后续是否重试由发送器和补偿任务决定。
     */
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
