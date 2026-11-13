package com.example.localtaskmessage.taskmessage.admin;

import com.example.localtaskmessage.taskmessage.core.TaskMessageStatus;

import java.time.LocalDateTime;

/**
 * 管理接口展示的消息任务状态和最近发送情况。
 *
 * @param taskId 消息任务唯一 ID，消费端用它去重
 * @param bizId 关联的业务编号，本例为订单号
 * @param topic 发送目标 Topic
 * @param status 当前发送状态，SENT 表示 MQ 已接收并已回写本地状态
 * @param retryCount 本轮累计发送失败次数，人工补发时会清零
 * @param nextRetryTime PENDING 状态下允许再次尝试发送的时间
 * @param lockedUntil PROCESSING 状态的租约到期时间，过期后可被接管，无租约时为空
 * @param lastError 最近一次记录的发送错误，可能为空
 * @param updateTime 任务最近一次更新的时间
 */
public record TaskMessageView(
        String taskId,
        String bizId,
        String topic,
        TaskMessageStatus status,
        int retryCount,
        LocalDateTime nextRetryTime,
        LocalDateTime lockedUntil,
        String lastError,
        LocalDateTime updateTime
) {}
