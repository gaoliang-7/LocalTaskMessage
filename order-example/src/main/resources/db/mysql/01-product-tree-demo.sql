-- 商品树 Demo：首次初始化脚本。

USE local_task_message_learning;

SET NAMES utf8mb4;

CREATE TABLE order_product_library
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    sku                   VARCHAR(50)  NOT NULL COMMENT '商品SKU，允许普通商品存在同SKU记录',
    product_name          VARCHAR(100) NOT NULL COMMENT '商品名称',
    product_type_category VARCHAR(20)  NOT NULL DEFAULT 'normal'
        COMMENT 'normal普通商品、similar同款、suite套装',
    del_flag              TINYINT      NOT NULL DEFAULT 0 COMMENT '0有效、1删除',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    KEY idx_product_sku (sku),

    CONSTRAINT chk_product_library_type
        CHECK (product_type_category IN ('normal', 'similar', 'suite')),

    CONSTRAINT chk_product_library_del
        CHECK (del_flag IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_bin
  COMMENT = '商品树演示商品表';


CREATE TABLE order_product_relation
(
    id                BIGINT   NOT NULL AUTO_INCREMENT COMMENT '关联ID',
    parent_product_id BIGINT   NOT NULL COMMENT '父商品ID',
    child_product_id  BIGINT   NOT NULL COMMENT '子商品ID',
    quantity          INT      NOT NULL DEFAULT 1 COMMENT '当前关联中的子商品数量',
    sort_order        INT      NOT NULL DEFAULT 0 COMMENT '同一父商品下的排序',
    del_flag          TINYINT  NOT NULL DEFAULT 0 COMMENT '0有效、1删除',
    create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    UNIQUE KEY uk_parent_child (
        parent_product_id,
        child_product_id,
        del_flag
        ),

    KEY idx_relation_parent_sort (
        parent_product_id,
        del_flag,
        sort_order,
        id
    ),

    CONSTRAINT chk_product_relation_quantity
        CHECK (quantity > 0),

    CONSTRAINT chk_product_relation_del
        CHECK (del_flag IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_bin
  COMMENT = '商品树演示父子关联表';


CREATE TABLE order_product_process_record
(
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    task_id       VARCHAR(64) NOT NULL COMMENT '消息任务ID',
    order_no      VARCHAR(64) NOT NULL COMMENT '订单号',
    process_order INT         NOT NULL COMMENT '本次任务的执行序号，从1开始',
    product_id    BIGINT      NOT NULL COMMENT '计划快照中的商品ID',
    sku           VARCHAR(50) NOT NULL COMMENT '计划快照中的SKU',
    product_type  VARCHAR(20) NOT NULL COMMENT '计划快照中的商品类型',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    UNIQUE KEY uk_process_task_order (
        task_id,
        process_order
        ),

    KEY idx_process_order_no (
        order_no,
        process_order
    ),

    CONSTRAINT chk_product_process_order
        CHECK (process_order > 0),

    CONSTRAINT chk_product_process_type
        CHECK (product_type IN ('normal', 'similar', 'suite'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_bin
  COMMENT = '订单商品处理记录';


-- 固定ID只用于演示，方便后续请求和查询。
-- 后续业务新增记录时，由数据库生成ID。

START TRANSACTION;

INSERT INTO order_product_library (
    id,
    sku,
    product_name,
    product_type_category,
    del_flag
)
VALUES
    (1001, 'DEMO-SUITE-A',   '示例套装A',     'suite',   0),
    (1002, 'DEMO-SIMILAR-B', '示例同款B',     'similar', 0),
    (1003, 'DEMO-LEAF-X',    '普通商品C',     'normal',  0),
    (1004, 'DEMO-LEAF-X',    '普通商品D同SKU', 'normal',  0);

INSERT INTO order_product_relation (
    id,
    parent_product_id,
    child_product_id,
    quantity,
    sort_order,
    del_flag
)
VALUES
    (2001, 1001, 1002, 1, 10, 0),
    (2002, 1002, 1003, 1, 10, 0),
    (2003, 1001, 1003, 2, 20, 0),
    (2004, 1001, 1004, 1, 30, 0);

COMMIT;