-- 在已经选中的已有数据库中执行。
-- 基线：五张业务表已存在，消息表已有状态、退避和租约字段。
-- 仅补充分片生成列、分片索引和缺失的消费组初始记录。
-- 执行前停止应用，执行后检查结构、数据和执行计划。

SET NAMES utf8mb4;

SET
@task_message_ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'local_task_message'
          AND column_name = 'house_number'
    ),
    'SELECT ''house_number exists: skipped'' AS migration_result',
    'ALTER TABLE local_task_message
        ADD COLUMN house_number TINYINT UNSIGNED
        GENERATED ALWAYS AS (CRC32(task_id) % 2) VIRTUAL'
);

PREPARE task_message_upgrade FROM @task_message_ddl;
EXECUTE task_message_upgrade;
DEALLOCATE PREPARE task_message_upgrade;

SET
@task_message_ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'local_task_message'
          AND column_name = 'retry_ready_at'
    ),
    'SELECT ''retry_ready_at exists: skipped'' AS migration_result',
    'ALTER TABLE local_task_message
        ADD COLUMN retry_ready_at DATETIME(3)
        GENERATED ALWAYS AS (
            CASE status
                WHEN ''PENDING'' THEN next_retry_time
                WHEN ''PROCESSING'' THEN locked_until
                ELSE NULL
            END
        ) VIRTUAL'
);

PREPARE task_message_upgrade FROM @task_message_ddl;
EXECUTE task_message_upgrade;
DEALLOCATE PREPARE task_message_upgrade;

SET
@task_message_ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'local_task_message'
          AND index_name = 'idx_task_house_ready'
    ),
    'SELECT ''idx_task_house_ready exists: skipped'' AS migration_result',
    'ALTER TABLE local_task_message
        ADD INDEX idx_task_house_ready (
            house_number,
            retry_ready_at,
            id
        )'
);

PREPARE task_message_upgrade FROM @task_message_ddl;
EXECUTE task_message_upgrade;
DEALLOCATE PREPARE task_message_upgrade;

INSERT INTO order_consume_stat (consumer_group,
                                processed_count)
VALUES ('order_created_consumer',
        0) ON DUPLICATE KEY
UPDATE
    consumer_group = 'order_created_consumer';