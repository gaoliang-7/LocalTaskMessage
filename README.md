# LocalTaskMessage

用订单示例演示本地消息表：订单和消息任务一起落库，事务提交后发送 RocketMQ，漏发或失败的消息由定时任务补发。

技术栈：Java 17、Spring Boot 4.1.2、MySQL 8.4、RocketMQ 5.x。

## 核心流程

1. **一起入库**：业务方法添加 `@Transactional` 和 `@LocalTaskMessage`，返回 `TaskMessageCommand`。切面在同一事务中登记消息，任一步失败都回滚。
2. **提交后发送**：事务提交后交给首发线程池；线程池接不下的任务留给定时补偿。
3. **抢占与重试**：发送前通过数据库抢占租约，回写时校验令牌和有效期。失败后按间隔翻倍重试，达到上限转为 `FAILED`，可人工补发并记录原因。
4. **消费去重**：消费记录以 `(consumer_group, task_id)` 为联合主键，与消费业务同事务提交，重复消息不重复处理。

正常状态：`PENDING` 待发送 → `PROCESSING` 发送中 → `SENT` MQ 已接收。MQ 确认后若本地回写失败，租约过期会再次补发，因此消费端仍需去重；`SENT` 不代表消费完成。

默认失败上限 **5 次**（含首发失败），重试间隔从 **10 秒**开始、最多 **60 秒**，租约 **30 秒**。补偿按 `CRC32(task_id) % 2` 分为两个分片。

## 项目结构

| 模块 | 负责什么 |
| --- | --- |
| `local-task-message-spring-boot-starter` | 消息注解、登记、发送、重试、RocketMQ 适配与自动配置 |
| `order-example` | 下单接口、订单消息消费、任务查询和人工补发 |

根 POM 管理模块和版本。Starter 复用接入应用的数据源与事务管理器，当前示例使用单数据源、单事务管理器。完整调用链见 [架构说明](docs/architecture.md)。

## 启动

先准备 JDK 17、Maven、MySQL 和 RocketMQ Proxy，并创建 `order_created` Topic、`order_created_consumer` 消费组。以下操作从项目根目录开始。

**1. 初始化数据库**

在 MySQL 客户端执行：

```sql
CREATE DATABASE local_task_message_learning
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE local_task_message_learning;
SOURCE docs/mysql/01-init.sql;
```

脚本创建订单、消息任务、消费记录、消费统计、人工补发记录五张表。已有旧库需补分片字段时，先停止应用，再执行 [分片升级脚本](docs/mysql/02-upgrade-sharding.sql)。

**2. 配置并运行**

配置入口：[application.yml](order-example/src/main/resources/application.yml)。默认连接本机 `local_task_message_learning` 数据库，MQ 使用 Proxy 的 gRPC 地址 `127.0.0.1:8081`。

```powershell
$env:DB_USERNAME = Read-Host '数据库用户名'
$databasePassword = Read-Host '数据库密码' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $databasePassword).Password
$env:ROCKETMQ_ENDPOINTS = '127.0.0.1:8081'

mvn clean package -DskipTests
java -jar .\order-example\target\order-example-0.0.1-SNAPSHOT.jar --server.port=18180
```

IDEA 可直接运行 `order-example` 模块的 `LocalTaskMessageApplication`，在运行配置中设置上述环境变量。启动参数 `--spring.profiles.active=retry-house-0` 或 `retry-house-1` 可让实例只负责对应分片；接管其他分片需调整配置并重启。

## 常用接口

按上面的命令启动后，地址为 `http://127.0.0.1:18180`，POST 请求使用 `Content-Type: application/json`。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/ping` | 检查 HTTP 服务是否可用 |
| POST | `/orders` | 下单，正文示例：`{"orderNo":"ORDER-001","userId":1001,"payAmount":12.34}` |
| GET | `/task-messages/stats` | 各状态任务数量 |
| GET | `/task-messages/page?status=PENDING&limit=20` | 按状态分页，下一页传入返回的 `nextBeforeId` 作为 `beforeId` |
| GET | `/task-messages/failed` | 最近 20 条停止自动重试的任务 |
| POST | `/task-messages/{taskId}/retry` | 人工补发，正文示例：`{"requestedBy":"admin","reason":"故障已恢复"}` |
| GET | `/task-messages/{taskId}/retry-records` | 最近 20 次人工补发记录 |

下单返回 **201** 表示订单和消息任务已提交；人工补发返回 **202** 表示已重置任务，等待补偿扫描。排查时用 `orderNo` 关联订单和任务，用 `taskId` 关联消费记录；日志中的 `messageId` 是 MQ 回执 ID。
