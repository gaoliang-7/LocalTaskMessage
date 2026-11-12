package com.example.localtaskmessage.taskmessage.spring;

import com.example.localtaskmessage.taskmessage.api.TaskMessageCommand;
import com.example.localtaskmessage.taskmessage.core.TaskMessageRegistrar;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Aspect
@Component
@Order(10)
public class LocalTaskMessageAspect {

    private final TaskMessageRegistrar registrar;

    public LocalTaskMessageAspect(TaskMessageRegistrar registrar) {
        this.registrar = registrar;
    }

    @Around("@annotation(com.example.localtaskmessage.taskmessage.api.LocalTaskMessage)")
    public Object registerMessage(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("@LocalTaskMessage 必须在活动事务中执行");
        }
        Object result = joinPoint.proceed();

        if (!(result instanceof TaskMessageCommand command)) {
            throw new IllegalStateException("@LocalTaskMessage 方法必须返回 TaskMessageCommand");
        }

        registrar.register(
                command.taskId(), command.bizId(), command.topic(), command.payload()
        );
        return result;
    }
}
