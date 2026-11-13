package com.example.localtaskmessage.taskmessage.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地消息的失败上限、重试间隔、首发线程池和补偿分组配置。
 *
 * @param maxFailures 累计发送失败多少次后停止自动重试，包含首发失败
 * @param initialDelaySeconds 第一次发送失败后等待的秒数
 * @param maxDelaySeconds 重试等待时间的上限，单位为秒
 * @param leaseSeconds 抢到任务后可持有的秒数，过期后其他线程可接管
 * @param sendExecutor 事务提交后，首次发送消息使用的线程池配置
 * @param retryGroups 当前实例负责运行的补偿任务组
 */
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

        @NotNull
        @Valid
        SendExecutor sendExecutor,

        @NotEmpty
        List<@NotNull @Valid RetryGroup> retryGroups
) {

    /**
     * 最大等待时间不能比第一次等待时间还短。
     */
    @AssertTrue(
            message = "max-delay-seconds 必须大于等于 initial-delay-seconds"
    )
    public boolean isDelayRangeValid() {
        return maxDelaySeconds >= initialDelaySeconds;
    }

    /**
     * 同一实例内每个补偿组的名字都要不同，方便按日志定位。
     */
    @AssertTrue(
            message = "同一实例内的补偿任务组 name 不能重复"
    )
    public boolean isRetryGroupNamesUnique() {
        // 缺失列表由 @NotEmpty 报错，这里只检查重名。
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

    /**
     * 首发线程池的线程数、排队长度和满载处理方式。
     *
     * @param corePoolSize 核心线程数
     * @param maxPoolSize 队列满后，线程最多增加到多少个
     * @param queueCapacity 最多排队多少条首发任务，0 表示不排队
     * @param rejectionPolicy 线程和队列都满时，交给补偿还是让提交线程自己发
     */
    public record SendExecutor(
            @Min(1) @Max(64)
            int corePoolSize,

            @Min(1) @Max(64)
            int maxPoolSize,

            @Min(0) @Max(10000)
            int queueCapacity,

            @NotNull
            RejectionPolicy rejectionPolicy
    ) {

        /**
         * 最大线程数不能小于核心线程数。
         */
        @AssertTrue(
                message = "max-pool-size 必须大于等于 core-pool-size"
        )
        public boolean isPoolSizeValid() {
            return maxPoolSize >= corePoolSize;
        }
    }

    /**
     * 首发线程池接不下任务时的处理方式。
     */
    public enum RejectionPolicy {
        /** 拒绝本次入队，保留消息任务，等待定时补偿。 */
        DEFER_TO_RETRY,
        /** 由提交任务的线程直接发送，会增加这次调用的耗时。 */
        CALLER_RUNS
    }

    /**
     * 一个补偿组只扫描自己负责的分片。
     *
     * @param name 补偿组名称，用来区分日志
     * @param houseNumber 分片号，由数据库按 CRC32(task_id) % 2 分到 0 或 1
     * @param batchSize 每轮最多查询并尝试发送多少条任务
     * @param fixedDelaySeconds 本轮结束后，再等多少秒开始下一轮
     */
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
