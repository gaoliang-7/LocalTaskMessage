package com.example.localtaskmessagelearning;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(
        prefix = "task-message",
        ignoreUnknownFields = false
)
public record TaskMessageProperties(
        @Min(1) @Max(30)
        int maxFailures,

        @Min(1) @Max(86400)
        int initialDelaySeconds,

        @Min(1) @Max(86400)
        int maxDelaySeconds,

        @Min(1) @Max(3600)
        int leaseSeconds
) {
    @AssertTrue(
            message = "max-delay-seconds 必须大于等于 initial-delay-seconds"
    )
    public boolean isDelayRangeValid() {
        return maxDelaySeconds >= initialDelaySeconds;
    }
}
