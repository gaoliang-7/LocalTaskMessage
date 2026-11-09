package com.example.localtaskmessagelearning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LocalTaskMessageLearningApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalTaskMessageLearningApplication.class, args);
    }

}
