package com.example.localtaskmessage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableTransactionManagement(order = 0)
public class LocalTaskMessageLearningApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalTaskMessageLearningApplication.class, args);
    }

}
