package com.example.localtaskmessage.taskmessage.core;

/**
 * 消息的发送进度；SENT 只表示 MQ 已接收，消费结果由消费端记录。
 */
public enum TaskMessageStatus {
    /** 等待首发，或等到重试时间后再发。 */
    PENDING,
    /** 已被某次发送抢到，租约过期后可以重新抢占。 */
    PROCESSING,
    /** MQ 已确认接收，且本地状态已回写成功。 */
    SENT,
    /** 失败次数达到上限，停止自动重试，等待人工处理。 */
    FAILED
}
