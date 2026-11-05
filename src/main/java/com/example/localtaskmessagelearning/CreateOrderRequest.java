package com.example.localtaskmessagelearning;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateOrderRequest(

        @NotBlank
        @Size(max = 64)
        String orderNo,

        @NotNull
        @Positive
        Long userId,

        @NotNull
        @Positive
        @Digits(integer = 8, fraction = 2)
        BigDecimal payAmount
) {
}
