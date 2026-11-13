package com.example.localtaskmessage.order.product;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProductRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Product> findById(long productId) {
        List<Product> products = jdbcTemplate.query("""
                SELECT id,
                       sku,
                       product_name,
                       product_type_category
                FROM order_product_library
                WHERE id = ?
                  AND del_flag = 0
                """,
                (rs, rowNum) -> new Product(
                        rs.getLong("id"),
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        ProductType.fromCode(
                                rs.getString("product_type_category")
                        )
                ),
                productId
        );

        return products.stream().findFirst();
    }

    public List<ProductRelation> findChildRelations(long parentProductId) {
        return jdbcTemplate.query("""
                SELECT id,
                       parent_product_id,
                       child_product_id,
                       quantity,
                       sort_order
                FROM order_product_relation
                WHERE parent_product_id = ?
                  AND del_flag = 0
                ORDER BY sort_order ASC, id ASC
                """,
                (rs, rowNum) -> new ProductRelation(
                        rs.getLong("id"),
                        rs.getLong("parent_product_id"),
                        rs.getLong("child_product_id"),
                        rs.getInt("quantity"),
                        rs.getInt("sort_order")
                ),
                parentProductId
        );
    }

}
