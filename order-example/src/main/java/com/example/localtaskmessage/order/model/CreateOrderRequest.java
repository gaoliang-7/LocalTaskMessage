package com.example.localtaskmessage.order.model;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * 下单接口接收的订单信息。
 *
 * @param orderNo 业务订单号，最多 64 个字符，数据库要求唯一
 * @param userId 下单用户 ID，必须大于 0
 * @param payAmount 订单金额，必须大于 0，最多 8 位整数和 2 位小数
 */
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
