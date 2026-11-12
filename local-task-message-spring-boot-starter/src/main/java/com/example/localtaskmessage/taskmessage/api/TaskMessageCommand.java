package com.example.localtaskmessage.taskmessage.api;

public record TaskMessageCommand(
        String taskId,
        String bizId,
        String topic,
        String payload
) {}
