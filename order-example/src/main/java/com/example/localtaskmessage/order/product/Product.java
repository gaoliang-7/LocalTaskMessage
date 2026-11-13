package com.example.localtaskmessage.order.product;

public record Product(
        long id,
        String sku,
        String productName,
        ProductType type
) {
}