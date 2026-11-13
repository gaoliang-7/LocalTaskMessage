package com.example.localtaskmessage.taskmessage.admin.web;

import com.example.localtaskmessage.taskmessage.admin.FailedTaskView;
import com.example.localtaskmessage.taskmessage.admin.RetryTaskRequest;
import com.example.localtaskmessage.taskmessage.admin.TaskMessageAdminService;
import com.example.localtaskmessage.taskmessage.admin.TaskMessagePage;
import com.example.localtaskmessage.taskmessage.admin.TaskMessageRetryRecordView;
import com.example.localtaskmessage.taskmessage.admin.TaskMessageView;
import com.example.localtaskmessage.taskmessage.core.TaskMessageStatus;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 提供消息任务查询、状态统计和人工补发接口。
 */
@RestController
@RequestMapping("/task-messages")
public class TaskMessageAdminController {

    private final TaskMessageAdminService adminService;

    public TaskMessageAdminController(TaskMessageAdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * 查看最近 20 条需要人工处理的失败任务。
     */
    @GetMapping("/failed")
    public List<FailedTaskView> failed() {
        return adminService.findFailed();
    }

    /**
     * 受理 FAILED 任务的人工补发，返回 202 后由定时补偿发送。
     */
    @PostMapping("/{taskId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> retry(
            @PathVariable String taskId,
            @Valid @RequestBody RetryTaskRequest request) {

        adminService.retry(taskId, request);

        return Map.of(
                "taskId", taskId,
                "result", "ACCEPTED"
        );

    }

    /**
     * 按状态查看最新 20 条任务。
     */
    @GetMapping
    public List<TaskMessageView> list(@RequestParam TaskMessageStatus status) {
        return adminService.findByStatus(status);
    }

    /**
     * 查看各状态的任务数量，便于发现积压和失败。
     */
    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return adminService.countByStatus();
    }

    /**
     * 按状态翻页，把上次返回的 nextBeforeId 作为本次 beforeId。
     */
    @GetMapping("/page")
    public TaskMessagePage page(
            @RequestParam("status") TaskMessageStatus status,
            @RequestParam(name = "beforeId", required = false) Long beforeId,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {

        return adminService.findPage(status, beforeId, limit);
    }

    /**
     * 查看一条任务最近 20 次人工补发的操作记录。
     */
    @GetMapping("/{taskId}/retry-records")
    public List<TaskMessageRetryRecordView> retryRecords(@PathVariable("taskId") String taskId) {
        return adminService.findRetryRecords(taskId);
    }
}
