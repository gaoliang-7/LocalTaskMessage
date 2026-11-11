package com.example.localtaskmessagelearning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class TaskMessageRetryJob {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageRetryJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final TaskMessageSender sender;

    public TaskMessageRetryJob(JdbcTemplate jdbcTemplate, TaskMessageSender sender) {
        this.jdbcTemplate = jdbcTemplate;
        this.sender = sender;
    }

    public void retryGroup(TaskMessageProperties.RetryGroup group) {
        long started = System.nanoTime();

        log.debug(
                "补偿组开始，group={}，houseNumber={}",
                group.name(),
                group.houseNumber()
        );

        try {
            List<String> taskIds = jdbcTemplate.queryForList("""
                            SELECT task_id
                            FROM local_task_message
                            WHERE house_number = ?
                              AND retry_ready_at <= CURRENT_TIMESTAMP(3)
                            ORDER BY retry_ready_at, id
                            LIMIT ?
                            """,
                    String.class,
                    group.houseNumber(),
                    group.batchSize()
            );

            if (taskIds.isEmpty()) return;

            log.info(
                    "补偿扫描命中，group={}，houseNumber={}，taskCount={}",
                    group.name(),
                    group.houseNumber(),
                    taskIds.size()
            );

            for (String taskId : taskIds) {
                try {
                    sender.send(taskId);
                } catch (Exception failure) {
                    log.error(
                            "补偿发送异常，group={}，houseNumber={}，taskId={}",
                            group.name(),
                            group.houseNumber(),
                            taskId,
                            failure
                    );
                }
            }

        } catch (Exception failure) {
            log.error(
                    "补偿组执行异常，group={}，houseNumber={}",
                    group.name(),
                    group.houseNumber(),
                    failure
            );
        } finally {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started
            );

            log.debug(
                    "补偿组结束，group={}，houseNumber={}，elapsedMillis={}",
                    group.name(),
                    group.houseNumber(),
                    elapsedMillis
            );
        }

//        for (int houseNumber : properties.retryHouseNumbers()) {
//            retryHouse(houseNumber);
//        }



//        List<String> taskIds = jdbcTemplate.queryForList("""
//                SELECT task_id
//                FROM local_task_message
//                WHERE (
//                    status = 'PENDING'
//                    AND next_retry_time <= CURRENT_TIMESTAMP(3)
//                )
//                OR (
//                    status = 'PROCESSING'
//                    AND locked_until <= CURRENT_TIMESTAMP(3)
//                )
//                ORDER BY id
//                LIMIT 20
//                """, String.class);
//
//        if (taskIds.isEmpty()) return;
//
//        log.info("本轮补偿任务数={}", taskIds.size());
//
//        for (String taskId : taskIds) {
//            try {
//                sender.send(taskId);
//            } catch (Exception e) {
//                log.error("补偿发送异常，taskId={}", taskId, e);
//
//            }
//        }

    }
//
//    private void retryHouse(int houseNumber) {
//        List<String> taskIds = jdbcTemplate.queryForList("""
//                SELECT task_id
//                FROM local_task_message
//                WHERE house_number = ?
//                  AND retry_ready_at <= CURRENT_TIMESTAMP(3)
//                ORDER BY retry_ready_at, id
//                LIMIT 20
//                """, String.class, houseNumber);
//
//        if (taskIds.isEmpty()) return;
//
//        log.info("本轮补偿命中，houseNumber={}，taskCount={}", houseNumber, taskIds.size());
//
//        for (String taskId : taskIds) {
//            try {
//                sender.send(taskId);
//            } catch (Exception e) {
//                log.error("补偿发送异常，houseNumber={}，taskId={}", houseNumber, taskId, e);
//
//            }
//        }
//    }

}
