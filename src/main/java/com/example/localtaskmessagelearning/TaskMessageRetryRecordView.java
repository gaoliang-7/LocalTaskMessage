package com.example.localtaskmessagelearning;

import java.time.LocalDateTime;

public record TaskMessageRetryRecordView(
        String requestedBy,
        String reason,
        int previousRetryCount,
        String previousError,
        LocalDateTime createTime
) {}
