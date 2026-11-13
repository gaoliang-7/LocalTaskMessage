package com.example.localtaskmessage.taskmessage.transport;

/**
 * 负责把消息交给 MQ，任务状态和重试由发送器处理。
 */
public interface TaskMessageTransport {

    /**
     * 发送一条消息并返回 MQ 回执；发送异常交给上层决定是否重试。
     *
     * @param taskId 重发时保持不变的任务 ID
     * @param topic 目标 Topic
     * @param payload 要发送的 JSON 正文
     */
    SendResult send(
            String taskId,
            String topic,
            String payload
    ) throws Exception;

    /**
     * MQ 接收消息后返回的回执。
     *
     * @param messageId MQ 生成的消息 ID，方便查日志，与业务 taskId 不同
     */
    record SendResult(String messageId){}
}
