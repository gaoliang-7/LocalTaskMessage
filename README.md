# LocalTaskMessage

Java 17 / Spring Boot 本地消息表示例，演示订单落库、事务提交后发送 RocketMQ 消息、失败补偿重试及消费端幂等处理。

## 运行环境

- Java 17
- Maven 3.9.x；IDEA 可使用项目配置的 Maven Wrapper 3.9.10
- MySQL
- RocketMQ 5.x Proxy

## 配置并启动

MySQL 中需要 `local_task_message_learning` 数据库，以及 `biz_order`、`local_task_message`、`message_consume_record`、`order_consume_stat` 四张表。消费统计表需要 `consumer_group = 'order_created_consumer'` 的初始化记录。

先启动 MySQL 和 RocketMQ，准备 `order_created` Topic 与 `order_created_consumer` 消费组，再配置环境变量：

```bash
export DB_USERNAME='your_db_user'
printf '数据库密码: '
read -r -s DB_PASSWORD
printf '\n'
export DB_PASSWORD
export ROCKETMQ_ENDPOINTS='127.0.0.1:8081'
mvn spring-boot:run
```

将数据库用户名和 MQ 地址替换为实际值。`ROCKETMQ_ENDPOINTS` 使用 Proxy 的 gRPC 地址，默认值为 `127.0.0.1:8081`。

在 IDEA 中运行主类 `com.example.localtaskmessagelearning.LocalTaskMessageLearningApplication`，并在运行配置中设置相同的环境变量。

机器相关的 Maven 仓库配置、IDEA 运行配置及服务器连接脚本保存在本机，并通过 `.gitignore` 排除。

## 验证

```bash
curl -f http://127.0.0.1:8080/ping
# TaskMessageLearning is Running!
```

## 常见问题

- IDEA 大量报 `Cannot resolve` 或提示语言级别为 5：检查 JDK 17、Maven Wrapper 3.9.10，并重新同步 Maven 项目。
- `Failed to fetch topics after 3 attempts`：检查 Proxy 连接地址、服务状态及 `order_created` Topic。
- `Access denied` 或用户名仍为 `${DB_USERNAME}`：检查当前启动进程的数据库环境变量。
- `消费统计初始化记录不存在`：检查 `order_consume_stat` 中的消费组初始化记录。
