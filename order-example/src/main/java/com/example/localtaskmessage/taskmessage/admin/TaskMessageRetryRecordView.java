package com.example.localtaskmessage.taskmessage.admin;

import java.time.LocalDateTime;

/**
 * 一次人工补发的记录，保留操作人、原因和补发前的失败情况。
 *
 * @param requestedBy 申请补发的人或系统标识
 * @param reason 申请时填写的补发原因
 * @param previousRetryCount 补发重置前的累计发送失败次数
 * @param previousError 补发重置前记录的错误，可能为空
 * @param createTime 本次人工补发记录的创建时间
 */
public record TaskMessageRetryRecordView(
        String requestedBy,
        String reason,
        int previousRetryCount,
        String previousError,
        LocalDateTime createTime
) {}
