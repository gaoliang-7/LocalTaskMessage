package com.example.localtaskmessage.taskmessage.admin;

import java.time.LocalDateTime;

public record FailedTaskView(
        String taskId,
        String bizId,
        String topic,
        int retryCount,
        String lastError,
        LocalDateTime updateTime
) {}
