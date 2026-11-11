package com.example.localtaskmessagelearning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreatedMessage(
        @NotBlank
        @Size(max = 64)
        String taskId,

        @NotBlank
        @Size(max = 64)
        String orderNo
) {

    public static final String TOPIC = "order_created";
}