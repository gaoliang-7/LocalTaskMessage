package com.example.localtaskmessage.taskmessage.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * RocketMQ 连接地址和生产者要发送的 Topic。
 *
 * @param endpoints Proxy 的 gRPC 地址，例如 127.0.0.1:8081
 * @param topics 生产者启动时登记的目标 Topic 列表
 */
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
