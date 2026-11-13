package com.example.localtaskmessage.taskmessage.admin;

import com.example.localtaskmessage.taskmessage.core.TaskMessageStatus;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询消息任务、统计状态，并把人工补发的任务重新交给定时补偿。
 */
@Service
public class TaskMessageAdminService {

    private final JdbcTemplate jdbcTemplate;

    public TaskMessageAdminService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询最近 20 条已停止自动重试的任务。
     */
    public List<FailedTaskView> findFailed() {
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

    /**
     * 把 FAILED 任务重置为待发送，同时保存补发记录；实际发送由补偿任务完成。
     */
    @Transactional
    public void retry(String taskId, RetryTaskRequest request) {
        // 先锁住这条任务，避免两次人工补发同时修改状态或记录不一致的旧值。
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

        // 失败次数清零并设为立即到期，补偿扫描时就能重新尝试发送。
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

        // 保存重置前的次数和错误；记录失败时，前面的状态重置也一起回滚。
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

    /**
     * 按状态取最新 20 条任务，更多数据通过分页接口查询。
     */
    public List<TaskMessageView> findByStatus(TaskMessageStatus status) {
        return findPage(status, null, 20).items();
    }

    /**
     * 按状态和数据库 ID 倒序翻页，不传 beforeId 时从最新任务开始。
     */
    public TaskMessagePage findPage(TaskMessageStatus status, Long beforeId, int limit) {
        if (limit < 1 || limit >100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须在 1 到 100 之间");
        }

        if (beforeId != null && beforeId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "beforeId 必须大于 0");
        }

        // beforeId 是上一页最后一条的 ID，减一后查询，避免两页边界重复。
        long upperId = beforeId == null ? Long.MAX_VALUE : beforeId - 1;

        // 多查一条来判断还有没有下一页，返回时只保留 limit 条。
        List<PageRow> rows = jdbcTemplate.query("""
            SELECT id, task_id, biz_id, topic, status, retry_count,
                   next_retry_time, locked_until, last_error, update_time
            FROM local_task_message
            WHERE status = ? AND id <= ?
            ORDER BY id DESC
            LIMIT ?
            """,
                (rs, rowNum) -> new PageRow(rs.getLong("id"), new TaskMessageView(
                        rs.getString("task_id"),
                        rs.getString("biz_id"),
                        rs.getString("topic"),
                        TaskMessageStatus.valueOf(rs.getString("status")),
                        rs.getInt("retry_count"),
                        rs.getObject("next_retry_time", LocalDateTime.class),
                        rs.getObject("locked_until", LocalDateTime.class),
                        rs.getString("last_error"),
                        rs.getObject("update_time", LocalDateTime.class)
                )),
                status.name(), upperId, limit + 1
        );

        List<TaskMessageView> items = rows.stream()
                .limit(limit)
                .map(PageRow::item)
                .toList();

        String nextBeforeId = rows.size() > limit
                ? Long.toString(rows.get(limit - 1).id())
                : null;

        return new TaskMessagePage(items, nextBeforeId);
    }

    /**
     * 统计每种状态的任务数，没有记录的状态也返回 0。
     */
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

    /**
     * 查询任务最近 20 次人工补发记录；任务不存在时返回 404。
     */
    public List<TaskMessageRetryRecordView> findRetryRecords(String taskId) {
        // 先区分“任务不存在”和“任务存在但没有人工补发记录”。
        List<String> taskIds = jdbcTemplate.queryForList("""
            SELECT task_id
            FROM local_task_message
            WHERE task_id = ?
            """, String.class, taskId);

        if (taskIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        }

        return jdbcTemplate.query("""
            SELECT requested_by, reason, previous_retry_count, previous_error, create_time
            FROM task_message_retry_record
            WHERE task_id = ?
            ORDER BY id DESC
            LIMIT 20
            """,
                (rs, rowNum) -> new TaskMessageRetryRecordView(
                        rs.getString("requested_by"),
                        rs.getString("reason"),
                        rs.getInt("previous_retry_count"),
                        rs.getString("previous_error"),
                        rs.getObject("create_time", LocalDateTime.class)
                ),
                taskIds.get(0)
        );
    }

    /**
     * 锁住任务后读出的旧值，供状态检查和补发记录使用。
     *
     * @param taskId 要补发的任务 ID
     * @param status 重置前的任务状态，只有 FAILED 允许补发
     * @param retryCount 重置前的累计发送失败次数
     * @param lastError 重置前记录的错误
     */
    private record RetrySnapshot(
            String taskId,
            String status,
            int retryCount,
            String lastError
    ) {}

    /**
     * 分页查询时同时保留数据库 ID 和展示数据。
     *
     * @param id 数据库主键，用来计算下一页游标
     * @param item 对外返回的任务信息
     */
    private record PageRow(long id, TaskMessageView item) {}

}
