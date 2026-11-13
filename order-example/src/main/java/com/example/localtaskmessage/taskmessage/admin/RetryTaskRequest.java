package com.example.localtaskmessage.taskmessage.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工补发时填写的操作信息。
 *
 * @param requestedBy 申请补发的人或系统标识，最多 64 个字符
 * @param reason 本次补发的原因，最多 256 个字符
 */
public record RetryTaskRequest(
        @NotBlank @Size(max = 64) String requestedBy,
        @NotBlank @Size(max = 256) String reason
) {}
