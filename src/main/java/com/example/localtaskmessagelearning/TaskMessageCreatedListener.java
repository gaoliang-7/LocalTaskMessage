package com.example.localtaskmessagelearning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TaskMessageCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageCreatedListener.class);

    private final TaskMessageSender sender;

    public TaskMessageCreatedListener(TaskMessageSender sender) {
        this.sender = sender;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(TaskMessageCreatedEvent event) {
        try {
            sender.send(event.taskId());
        } catch (Exception e) {
            log.error("消息发送处理异常，taskId={}", event.taskId(), e);
        }
    }

}
