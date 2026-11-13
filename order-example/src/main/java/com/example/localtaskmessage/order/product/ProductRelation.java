package com.example.localtaskmessage.order.product;

public record ProductRelation(
        long id,
        long parentProductId,
        long childProductId,
        int quantity,
        int sortOrder
) {
}