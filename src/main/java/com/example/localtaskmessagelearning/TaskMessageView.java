package com.example.localtaskmessagelearning;

import java.time.LocalDateTime;

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