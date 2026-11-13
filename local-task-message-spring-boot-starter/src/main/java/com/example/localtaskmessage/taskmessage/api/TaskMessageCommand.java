package com.example.localtaskmessage.taskmessage.api;

/**
 * 业务方法交给切面的消息内容，切面负责把它存成待发送任务。
 *
 * @param taskId 消息任务唯一 ID，重发时保持不变，消费端用它去重
 * @param bizId 关联的业务编号，本例是订单号
 * @param topic 消息要发到的 Topic
 * @param payload 已转成 JSON 的消息正文
 */
public record TaskMessageCommand(
        String taskId,
        String bizId,
        String topic,
        String payload
) {}
