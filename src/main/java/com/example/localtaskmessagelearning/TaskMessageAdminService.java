package com.example.localtaskmessagelearning;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskMessageAdminService {

    private final JdbcTemplate jdbcTemplate;

    public TaskMessageAdminService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<FailedTaskView> findFailed() {
        // 最近20条；失败任务增多时增加游标分页。
        return jdbcTemplate.query("""
                SELECT task_id, biz_id, topic,
                       retry_count, last_error, update_time
                FROM local_task_message
                WHERE status = 'FAILED'
                ORDER BY id DESC
                LIMIT 20
                """,
                (rs, rowNum) -> new FailedTaskView(
                        rs.getString("task_id"),
                        rs.getString("biz_id"),
                        rs.getString("topic"),
                        rs.getInt("retry_count"),
                        rs.getString("last_error"),
                        rs.getTimestamp("update_time").toLocalDateTime()
                )
        );
    }

    @Transactional
    public void retry(String taskId, RetryTaskRequest request) {
        List<RetrySnapshot> tasks = jdbcTemplate.query("""
                SELECT task_id, status, retry_count, last_error
                FROM local_task_message
                WHERE task_id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new RetrySnapshot(
                        rs.getString("task_id"),
                        rs.getString("status"),
                        rs.getInt("retry_count"),
                        rs.getString("last_error")
                ),
                taskId
        );

        if (tasks.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "任务不存在"
            );
        }

        RetrySnapshot task = tasks.get(0);

        if (!"FAILED".equals(task.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "只有FAILED任务允许人工补发"
            );
        }

        int updated = jdbcTemplate.update("""
                UPDATE local_task_message
                SET status = 'PENDING',
                    retry_count = 0,
                    next_retry_time = CURRENT_TIMESTAMP,
                    last_error = NULL,
                    lock_token = NULL,
                    locked_until = NULL,
                    update_time = CURRENT_TIMESTAMP
                WHERE task_id = ?
                  AND status = 'FAILED'
                """,
                task.taskId()
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "补发状态更新异常，taskId=" + task.taskId()
            );
        }

        jdbcTemplate.update("""
                INSERT INTO task_message_retry_record (
                    task_id,
                    requested_by,
                    reason,
                    previous_retry_count,
                    previous_error
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                task.taskId(),
                request.requestedBy(),
                request.reason(),
                task.retryCount(),
                task.lastError()
        );

    }

    public List<TaskMessageView> findByStatus(TaskMessageStatus status) {
        return jdbcTemplate.query("""
            SELECT task_id, biz_id, topic, status, retry_count,
                   next_retry_time, locked_until, last_error, update_time
            FROM local_task_message
            WHERE status = ?
            ORDER BY id DESC
            LIMIT 20
            """,
                (rs, rowNum) -> new TaskMessageView(
                        rs.getString("task_id"),
                        rs.getString("biz_id"),
                        rs.getString("topic"),
                        TaskMessageStatus.valueOf(rs.getString("status")),
                        rs.getInt("retry_count"),
                        rs.getObject("next_retry_time", LocalDateTime.class),
                        rs.getObject("locked_until", LocalDateTime.class),
                        rs.getString("last_error"),
                        rs.getObject("update_time", LocalDateTime.class)
                ),
                status.name()
        );
    }


    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();

        for (TaskMessageStatus status : TaskMessageStatus.values()) {
            counts.put(status.name(), 0L);
        }

        jdbcTemplate.query("""
            SELECT status, COUNT(*) AS task_count
            FROM local_task_message
            GROUP BY status
            """,
                rs -> {
                    counts.put(
                            rs.getString("status"),
                            rs.getLong("task_count")
                    );
                }
        );

        return counts;
    }

    private record RetrySnapshot(
            String taskId,
            String status,
            int retryCount,
            String lastError
    ) {}

}
