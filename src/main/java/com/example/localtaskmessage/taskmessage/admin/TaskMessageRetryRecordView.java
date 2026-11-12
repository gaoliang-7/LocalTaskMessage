package com.example.localtaskmessage.taskmessage.admin;

import java.time.LocalDateTime;

public record TaskMessageRetryRecordView(
        String requestedBy,
        String reason,
        int previousRetryCount,
        String previousError,
        LocalDateTime createTime
) {}
