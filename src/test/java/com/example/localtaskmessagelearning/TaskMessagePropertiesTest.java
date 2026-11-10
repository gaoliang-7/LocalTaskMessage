package com.example.localtaskmessagelearning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TaskMessagePropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfig.class)
                    .withPropertyValues(
                            "task-message.max-failures=5",
                            "task-message.initial-delay-seconds=10",
                            "task-message.max-delay-seconds=60",
                            "task-message.lease-seconds=30"
                    );

    @Test
    void shouldBindValidConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .hasSingleBean(TaskMessageProperties.class);

            TaskMessageProperties properties =
                    context.getBean(TaskMessageProperties.class);

            assertThat(properties.maxFailures()).isEqualTo(5);
            assertThat(properties.initialDelaySeconds()).isEqualTo(10);
            assertThat(properties.maxDelaySeconds()).isEqualTo(60);
            assertThat(properties.leaseSeconds()).isEqualTo(30);
        });
    }

    @ParameterizedTest(name = "[{index}] 拒绝 {0}={1}")
    @CsvSource({
            "max-failures, -1, maxFailures",
            "max-failures, 0, maxFailures",
            "max-failures, 31, maxFailures",
            "initial-delay-seconds, -1, initialDelaySeconds",
            "initial-delay-seconds, 0, initialDelaySeconds",
            "initial-delay-seconds, 86401, initialDelaySeconds",
            "max-delay-seconds, -1, maxDelaySeconds",
            "max-delay-seconds, 0, maxDelaySeconds",
            "max-delay-seconds, 86401, maxDelaySeconds",
            "lease-seconds, -1, leaseSeconds",
            "lease-seconds, 0, leaseSeconds",
            "lease-seconds, 3601, leaseSeconds"
    })
    void shouldRejectOutOfRangeValues(String key, int value, String field) {
        runner.withPropertyValues("task-message." + key + "=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    BindValidationException.class
                            )
                            .hasStackTraceContaining(field);
                });
    }

    @ParameterizedTest(name = "[{index}] 接受配置 {0}/{1}/{2}/{3}")
    @CsvSource({
            "1, 10, 60, 30",
            "30, 10, 60, 30",
            "5, 1, 60, 30",
            "5, 86400, 86400, 30",
            "5, 1, 1, 30",
            "5, 10, 86400, 30",
            "5, 10, 60, 1",
            "5, 10, 60, 3600"
    })
    void shouldAcceptBoundaryValues(
            int maxFailures,
            int initialDelaySeconds,
            int maxDelaySeconds,
            int leaseSeconds) {

        runner.withPropertyValues(
                        "task-message.max-failures=" + maxFailures,
                        "task-message.initial-delay-seconds=" + initialDelaySeconds,
                        "task-message.max-delay-seconds=" + maxDelaySeconds,
                        "task-message.lease-seconds=" + leaseSeconds
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TaskMessageProperties.class))
                            .isEqualTo(new TaskMessageProperties(
                                    maxFailures,
                                    initialDelaySeconds,
                                    maxDelaySeconds,
                                    leaseSeconds
                            ));
                });
    }

    @Test
    void shouldRejectMaxDelayBelowInitialDelay() {
        runner.withPropertyValues(
                        "task-message.initial-delay-seconds=20",
                        "task-message.max-delay-seconds=10"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    BindValidationException.class
                            )
                            .hasStackTraceContaining("delayRangeValid");
                });
    }

    @Test
    void shouldRejectUnknownKey() {
        runner.withPropertyValues("task-message.unexpected-key=1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    UnboundConfigurationPropertiesException.class
                            )
                            .hasStackTraceContaining(
                                    "task-message.unexpected-key"
                            );
                });
    }

    @Test
    void shouldRejectMissingLease() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(
                        "task-message.max-failures=5",
                        "task-message.initial-delay-seconds=10",
                        "task-message.max-delay-seconds=60"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    BindValidationException.class
                            )
                            .hasStackTraceContaining("leaseSeconds");
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TaskMessageProperties.class)
    static class TestConfig {}
}
