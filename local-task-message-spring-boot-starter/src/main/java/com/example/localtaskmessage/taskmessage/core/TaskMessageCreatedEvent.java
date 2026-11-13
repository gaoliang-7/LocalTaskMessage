package com.example.localtaskmessage.taskmessage.core;

/**
 * 通知监听器有新任务，等业务事务提交后再安排发送。
 *
 * @param taskId 刚写入消息表的任务 ID
 */
public record TaskMessageCreatedEvent(String taskId) {
}
