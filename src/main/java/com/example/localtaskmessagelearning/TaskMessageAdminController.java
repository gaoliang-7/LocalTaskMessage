package com.example.localtaskmessagelearning;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/task-messages")
public class TaskMessageAdminController {

    private final TaskMessageAdminService adminService;

    public TaskMessageAdminController(TaskMessageAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/failed")
    public List<FailedTaskView> failed() {
        return adminService.findFailed();
    }

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

    @GetMapping
    public List<TaskMessageView> list(@RequestParam TaskMessageStatus status) {
        return adminService.findByStatus(status);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return adminService.countByStatus();
    }

    @GetMapping("/page")
    public TaskMessagePage page(
            @RequestParam("status") TaskMessageStatus status,
            @RequestParam(name = "beforeId", required = false) Long beforeId,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {

        return adminService.findPage(status, beforeId, limit);
    }

    @GetMapping("/{taskId}/retry-records")
    public List<TaskMessageRetryRecordView> retryRecords(@PathVariable("taskId") String taskId) {
        return adminService.findRetryRecords(taskId);
    }
}
