package com.example.localtaskmessage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.example.localtaskmessage.order",
        "com.example.localtaskmessage.support",
        "com.example.localtaskmessage.taskmessage.admin"
})
public class LocalTaskMessageApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalTaskMessageApplication.class, args);
    }

}
