package com.example.localtaskmessagelearning;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        int leaseSeconds,

        @NotEmpty
        List<@NotNull @Valid RetryGroup> retryGroups
) {

    @AssertTrue(
            message = "max-delay-seconds 必须大于等于 initial-delay-seconds"
    )
    public boolean isDelayRangeValid() {
        return maxDelaySeconds >= initialDelaySeconds;
    }

    @AssertTrue(
            message = "同一实例内的补偿任务组 name 不能重复"
    )
    public boolean isRetryGroupNamesUnique() {
        if (retryGroups == null) {
            return true;
        }

        Set<String> names = new HashSet<>();

        for (RetryGroup group : retryGroups) {
            if (group != null && !names.add(group.name())) {
                return false;
            }
        }

        return true;
    }

    public record RetryGroup(
            @NotBlank
            @Size(max = 64)
            String name,

            @NotNull
            @Min(0) @Max(1)
            Integer houseNumber,

            @Min(1) @Max(100)
            int batchSize,

            @Min(1) @Max(86400)
            int fixedDelaySeconds
    ) {
    }
}
