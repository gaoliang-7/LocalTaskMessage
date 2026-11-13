package com.example.localtaskmessage.taskmessage.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在有事务的方法上，把返回的 TaskMessageCommand 跟业务数据一起落库。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LocalTaskMessage {
}
