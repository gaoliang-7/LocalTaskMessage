package com.example.localtaskmessage.order.product;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProductType {

    NORMAL("normal"),
    SIMILAR("similar"),
    SUITE("suite");

    private final String code;

    ProductType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public boolean isComposite() {
        return this == SIMILAR || this == SUITE;
    }

    @JsonCreator
    public static ProductType fromCode(String code) {
        for (ProductType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }

        throw new IllegalArgumentException("不支持的商品类型：" + code);
    }

}
