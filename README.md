# LocalTaskMessage

Spring Boot 4.1.2 / Java 17 本地消息表项目：订单和消息任务同事务落库，提交后发送 RocketMQ，分片任务补偿失败消息，消费端通过唯一键保证幂等。

## 模块

| 模块 | 职责 | 产物 |
| --- | --- | --- |
| 根项目 | Maven 聚合与版本管理 | pom |
| local-task-message-spring-boot-starter | 注解登记、发送状态机、RocketMQ 适配、首发线程池、补偿组和自动配置 | 普通依赖 JAR |
| order-example | 订单 API、Topic、消息正文、消费业务、管理 API 和启动入口 | 可执行 Spring Boot JAR |

Starter 使用宿主的数据源、JdbcTemplate 和 JDBC 事务管理器，不创建第二套数据源。当前示例采用单数据源和单事务管理器。模块边界和调用链见 [docs/architecture.md](docs/architecture.md)。

## 数据库入口

需要 MySQL 8.4、RocketMQ 5.x Proxy、`order_created` Topic 和 `order_created_consumer` 消费组。

- 空库：MySQL 客户端执行 `SOURCE H:/LocalTaskMessage/TaskMessage/docs/mysql/01-init.sql;`。
- 已有库补分片字段：执行 `SOURCE H:/LocalTaskMessage/TaskMessage/docs/mysql/02-upgrade-sharding.sql;`。

初始化脚本包含 biz_order、local_task_message、message_consume_record、order_consume_stat、task_message_retry_record 五张表及消费统计初始记录。已有学习库已经包含分片字段和索引时，无需重复执行。第五步模块拆分不修改数据库结构。

## Windows 构建

```powershell
Set-Location 'H:\LocalTaskMessage\TaskMessage'
$env:JAVA_HOME = 'G:\env\JDK17'
$env:Path = $env:JAVA_HOME + '\bin;' + $env:Path
& 'G:\env\maven\apache-maven-3.9.12-bin\apache-maven-3.9.12\bin\mvn.cmd' `
    -o '-Dmaven.repo.local=G:\env\maven\apache-maven-3.6.3\conf\repository' `
    clean package -DskipTests
```

其他机器配置好 JDK 17 和 Maven 后，可执行 `mvn clean package -DskipTests`。其他项目依赖此 Starter 前，需要执行根项目 `mvn install -DskipTests`，或将普通 Starter JAR 发布到宿主可访问的 Maven 仓库。

## IDEA 启动

1. 重新加载根 pom.xml 的 Maven 项目。
2. 运行 order-example 模块的 `com.example.localtaskmessage.LocalTaskMessageApplication`。
3. Run Configuration 的 classpath 选择 order-example，保留已有 DB_USERNAME、DB_PASSWORD 环境变量。
4. ROCKETMQ_ENDPOINTS 使用 Proxy 的 gRPC 地址，默认 127.0.0.1:8081。

主类只扫描订单、支持组件和管理 API。Starter 通过 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 发现，不依赖扩大组件扫描范围。

## PowerShell 启动

IDEA 运行配置中的环境变量不会自动传入 PowerShell；终端可交互输入账号：

```powershell
$env:DB_USERNAME = Read-Host '数据库用户名'
$databasePassword = Read-Host '数据库密码' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $databasePassword).Password
$env:ROCKETMQ_ENDPOINTS = '127.0.0.1:8081'

& 'G:\env\JDK17\bin\java.exe' -jar `
    'H:\LocalTaskMessage\TaskMessage\order-example\target\order-example-0.0.1-SNAPSHOT.jar' `
    --server.port=18180
```

启动日志应出现“装配本地消息组件”“注册补偿组”及数据库检查的 registrarProxy=true。追加 --debug 可查看自动配置匹配结果。

配置入口为 order-example/src/main/resources/application.yml：spring.datasource 配置宿主数据源；rocketmq.endpoints 和 rocketmq.topics 配置 Producer；task-message.send-executor 配置首发背压；task-message.retry-groups 配置补偿分工。

默认两个组负责门牌号 0 和 1。通过 --spring.profiles.active=retry-house-0 或 retry-house-1 让实例只扫描指定分片。分片接管需要让存活实例配置覆盖目标门牌号并重启。

## HTTP 检查

```powershell
Invoke-RestMethod 'http://127.0.0.1:18180/ping'
$orderNumber = 'STEP5-' + (Get-Date).ToString('yyyyMMddHHmmssfff')
$requestBody = @{ orderNo = $orderNumber; userId = 1001; payAmount = 12.34 } | ConvertTo-Json
Invoke-WebRequest -UseBasicParsing -Method Post `
    -Uri 'http://127.0.0.1:18180/orders' -ContentType 'application/json' -Body $requestBody
Invoke-RestMethod 'http://127.0.0.1:18180/task-messages/stats'
Invoke-RestMethod 'http://127.0.0.1:18180/task-messages/page?status=SENT&limit=20'
```

HTTP 201 表示订单和任务登记已提交，发送和消费随后完成。按订单号查询 biz_order、local_task_message.biz_id，按 task_id 查询 message_consume_record。日志 messageId 是 Broker 回执，task_id 是消费幂等使用的业务消息标识。

## 常见定位

- 找不到 TaskMessageRegistrar：检查 Starter 依赖、imports 资源，以及数据源、事务管理器和 Transport 条件。
- “@LocalTaskMessage 必须在活动事务中执行”：方法需要 @Transactional 并从 Spring 代理外部调用；当前事务增强顺序 0，登记切面顺序 10。
- Failed to fetch topics / 连接失败：检查 Proxy gRPC 端口和 Topic，Producer 启动时会连接 MQ。
- “消费统计初始化记录不存在”：检查 order_consume_stat 的消费组初始记录。
- “首发未入队，交由补偿”：观察对应门牌号补偿日志和 retry_ready_at。
- “MQ 已确认，SENT 回写异常”：保留租约，过期后可能重复发送；结合 taskId、Broker 回执及幂等记录定位。

第五步验收和编号记录见 [docs/progress.md](docs/progress.md)。
