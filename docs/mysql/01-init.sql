-- 仅在已经选中的空数据库中执行。
-- 数据库的创建和选择由 README 中的执行入口负责。
-- 本文件不包含 DROP TABLE，也不用于升级已有表。

SET NAMES utf8mb4;

CREATE TABLE biz_order
(
    id          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
    order_no    VARCHAR(64)    NOT NULL COMMENT '业务订单号',
    user_id     BIGINT         NOT NULL COMMENT '用户ID',
    pay_amount  DECIMAL(10, 2) NOT NULL COMMENT '订单金额',
    create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '业务订单表';

CREATE TABLE local_task_message
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
    task_id         VARCHAR(64)  NOT NULL COMMENT '消息任务唯一标识',
    biz_id          VARCHAR(64)  NOT NULL COMMENT '关联的业务标识，本例为订单号',
    topic           VARCHAR(128) NOT NULL COMMENT 'RocketMQ目标Topic',
    payload         JSON         NOT NULL COMMENT '待发送的消息内容',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待发送，PROCESSING处理中，SENT已发送，FAILED终止重试',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '发送失败次数',
    next_retry_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '允许再次发送的时间',
    last_error      VARCHAR(512)          DEFAULT NULL COMMENT '最近一次发送错误',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_token      VARCHAR(64)           DEFAULT NULL COMMENT '本次抢占的唯一令牌',
    locked_until    DATETIME(3) DEFAULT NULL COMMENT '租约到期时间',
    house_number    TINYINT UNSIGNED
        GENERATED ALWAYS AS (CRC32(task_id) % 2) VIRTUAL,
    retry_ready_at  DATETIME(3)
        GENERATED ALWAYS AS (
            CASE status
                WHEN 'PENDING' THEN next_retry_time
                WHEN 'PROCESSING' THEN locked_until
                ELSE NULL
            END
        ) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    KEY             idx_pending_retry (status, next_retry_time, id),
    KEY             idx_processing_lease (status, locked_until, id),
    KEY             idx_task_status_id (status, id),
    KEY             idx_task_house_ready (house_number, retry_ready_at, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '本地消息任务表';

CREATE TABLE message_consume_record
(
    consumer_group VARCHAR(64) NOT NULL COMMENT '消费组',
    task_id        VARCHAR(64) NOT NULL COMMENT '业务消息任务ID',
    order_no       VARCHAR(64) NOT NULL COMMENT '关联订单号',
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_group, task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_bin
  COMMENT = '消费幂等记录';

CREATE TABLE order_consume_stat
(
    consumer_group  VARCHAR(64) NOT NULL,
    processed_count BIGINT      NOT NULL DEFAULT 0,
    update_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_group)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_bin
  COMMENT = '订单消息处理统计';

CREATE TABLE task_message_retry_record
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    task_id              VARCHAR(64)  NOT NULL,
    requested_by         VARCHAR(64)  NOT NULL,
    reason               VARCHAR(256) NOT NULL,
    previous_retry_count INT          NOT NULL,
    previous_error       VARCHAR(512)          DEFAULT NULL,
    create_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY                  idx_task_retry_record (task_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_bin;

INSERT INTO order_consume_stat (consumer_group,
                                processed_count)
VALUES ('order_created_consumer',
        0);