package com.example.localtaskmessagelearning;

public interface TaskMessageTransport {

    SendResult send(
            String taskId,
            String topic,
            String payload
    ) throws Exception;

    record SendResult(String messageId){}
}
