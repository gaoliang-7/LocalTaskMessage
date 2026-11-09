package com.example.localtaskmessagelearning;

import jakarta.validation.Valid;
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

    public Map<String, String> retry(
            @PathVariable String taskId,
            @Valid @RequestBody RetryTaskRequest request) {

        adminService.retry(taskId, request);

        return Map.of(
                "taskId", taskId,
                "result", "ACCEPTED"
        );

    }
}
