package com.example.localtaskmessage.order.product;

import java.util.List;
import java.util.Objects;

public record ProductTreeNode(
        Product product,
        ProductRelation incomingRelation,
        List<ProductTreeNode> children
) {

    public ProductTreeNode {
        Objects.requireNonNull(product, "节点商品不能为空");
        children = List.copyOf(children);
    }
}