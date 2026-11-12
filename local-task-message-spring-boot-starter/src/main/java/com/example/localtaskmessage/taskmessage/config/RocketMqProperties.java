package com.example.localtaskmessage.taskmessage.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(
        prefix = "rocketmq",
        ignoreUnknownFields = false
)
public record RocketMqProperties(
        @NotBlank String endpoints,
        @NotEmpty List<@NotBlank String> topics
) {
}