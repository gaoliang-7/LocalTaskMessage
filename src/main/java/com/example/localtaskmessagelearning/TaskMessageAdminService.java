package com.example.localtaskmessagelearning;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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

    private record RetrySnapshot(
            String taskId,
            String status,
            int retryCount,
            String lastError
    ) {}

}
