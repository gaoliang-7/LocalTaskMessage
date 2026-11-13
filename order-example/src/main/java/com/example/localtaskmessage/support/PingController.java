package com.example.localtaskmessage.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供简单的 HTTP 探活入口。
 */
@RestController
public class PingController {

    /**
     * 返回固定文本，确认应用能响应 HTTP 请求。
     */
    @GetMapping("/ping")
    public String ping() {
        return "TaskMessageLearning is Running!";
    }
}
