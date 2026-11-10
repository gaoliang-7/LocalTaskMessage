package com.example.localtaskmessagelearning;

public record TaskMessageCommand(
        String taskId,
        String bizId,
        String topic,
        String payload
) {}