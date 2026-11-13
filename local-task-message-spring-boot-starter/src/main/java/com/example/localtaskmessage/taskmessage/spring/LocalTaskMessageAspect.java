package com.example.localtaskmessage.taskmessage.spring;

import com.example.localtaskmessage.taskmessage.api.TaskMessageCommand;
import com.example.localtaskmessage.taskmessage.core.TaskMessageRegistrar;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 业务方法执行成功后，在同一个事务里补写消息任务。
 */
@Aspect
// 事务增强的顺序是 0，切面是 10：先开事务，再执行业务和登记消息。
@Order(10)
public class LocalTaskMessageAspect {

    private final TaskMessageRegistrar registrar;

    public LocalTaskMessageAspect(TaskMessageRegistrar registrar) {
        this.registrar = registrar;
    }

    /**
     * 先执行业务，再登记返回的消息；中途出错时，两边一起回滚。
     */
    @Around("@annotation(com.example.localtaskmessage.taskmessage.api.LocalTaskMessage)")
    public Object registerMessage(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("@LocalTaskMessage 必须在活动事务中执行");
        }
        // 业务抛出异常就直接退出，不登记消息，让外层事务负责回滚。
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
