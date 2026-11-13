package com.example.localtaskmessage.taskmessage.admin;

import java.time.LocalDateTime;

/**
 * 已停止自动重试的任务，供人工查看和处理。
 *
 * @param taskId 消息任务唯一 ID
 * @param bizId 关联的业务编号，本例为订单号
 * @param topic 发送目标 Topic
 * @param retryCount 本轮累计发送失败次数，包含首发失败
 * @param lastError 最近一次记录的发送错误
 * @param updateTime 任务最近一次更新的时间
 */
public record FailedTaskView(
        String taskId,
        String bizId,
        String topic,
        int retryCount,
        String lastError,
        LocalDateTime updateTime
) {}
