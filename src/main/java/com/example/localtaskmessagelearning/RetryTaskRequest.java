package com.example.localtaskmessagelearning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RetryTaskRequest(
        @NotBlank @Size(max = 64) String requestedBy,
        @NotBlank @Size(max = 256) String reason
) {}