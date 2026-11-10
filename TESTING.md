配置、发送、补发、消费和任务查询测试使用独立测试环境。可以在项目目录执行以下命令。

```powershell
Set-Location 'H:\LocalTaskMessage\TaskMessage'
mvn -B test "-Dtest=TaskMessagePropertiesTest,TaskMessageSenderTest,TaskMessageAdminTest,OrderCreatedConsumerTest,TaskMessageQueryTest"
```

预期执行 84 次。参数化方法中的每行数据都计为一次执行。

`TaskMessagePropertiesTest` 用 `ApplicationContextRunner` 创建小容器，验证配置绑定、合法边界、非法范围、跨字段关系、未知键和缺少配置。负向用例会打印配置启动异常，最终 JUnit 结果仍应全部通过。

`TaskMessageSenderTest` 直接调用项目实际的发送类。每次测试创建独立 H2 内存数据库并在结束后关闭，`JdbcTemplate` 执行真实 SQL；`Producer` 使用 Mockito 替身控制发送成功或失败，不建立真实 MQ 连接。H2 依赖仅用于测试。

| 发送端测试 | 验证行为 |
| --- | --- |
| `shouldSendStoredMessageAndMarkSent` | 发出的 Topic、Key 和正文来自任务；成功后变为 SENT，清理错误与租约，保留历史失败次数 |
| `shouldIncreaseBackoffAndCapAtMaximum` | 四组历史失败次数，验证等待 10/20/40/60 秒、计数增加、错误保存与租约释放 |
| `shouldUseConfiguredLeaseAndFailurePolicy` | 改为失败三次、等待 2/3 秒、七秒租约，确认 SQL 使用配置并在 FAILED 后停止发送 |
| `shouldRecoverAfterDueRetry` | 第一次失败，冷却期跳过，到期后由补偿任务成功发送 |
| `shouldSkipUnclaimableTasks` | 跳过 SENT、FAILED、未到期任务、有效租约和不存在的任务 |
| `shouldReclaimExpiredLease` | 定时任务找出过期 PROCESSING，重新抢占并发送 |
| `shouldNotOverwriteNewOwnerAfterLeaseChanges` | 模拟发送过程中令牌改变，旧的成功或失败结果都不能覆盖新持有者 |
| `shouldFailOnFirstAttemptWhenLimitIsOne` | 失败上限为一时，第一次失败就进入 FAILED |

发送端测试可以先从 `shouldIncreaseBackoffAndCapAtMaximum` 开始，按三个阶段读代码：

1. 准备：插入一条到期任务、设置历史失败次数，让 `producer.send` 抛出指定异常。
2. 执行：调用实际的 `sender.send`，断言原异常继续抛出。
3. 断言：从数据库读取状态、计数、错误与租约，并用 Mockito 确认发送调用次数。

等待时间的断言比较同一次 UPDATE 写入的 `update_time` 和 `next_retry_time`。恢复测试只将自己的任务设为到期，不依赖 `Thread.sleep`。

单独运行这组退避测试：

```powershell
mvn -B test "-Dtest=TaskMessageSenderTest#shouldIncreaseBackoffAndCapAtMaximum"
```

预期执行四次。在 IDEA 中也可以点击测试类或方法旁的运行按钮。

H2 的 MySQL 兼容模式可用于快速回归，真实 MySQL 的赋值顺序、锁等待和双 JVM 竞争仍需独立集成测试验证。这里控制令牌变化的用例只验证回写保护，不代表已经进行了真实并发调度测试。

`TaskMessageAdminTest` 使用 MockMvc 调用实际控制器，在独立 H2 数据库中验证补发流程。服务从开启事务管理的 Spring 容器中取得，调用会经过事务代理；没有模拟服务的数据库操作。

| 人工补发场景 | 验证行为 |
| --- | --- |
| 失败列表 | 只返回最近 20 条 FAILED，不返回消息正文 |
| 合法补发 | HTTP 202；重置为 PENDING，沿用消息标识与内容，保存旧失败次数和错误 |
| 非 FAILED 状态 | PENDING、PROCESSING、SENT 各返回 409，任务不变 |
| 任务不存在 | HTTP 404，不创建操作记录 |
| 请求非法 | 操作人或原因缺失、空白、超长，共六种输入返回 400，任务不变 |
| 操作记录插入失败 | 由真实数据库约束制造 INSERT 异常，任务更新和操作记录一起回滚 |
| 两个并发请求 | 屏障同时放行两个请求，一个返回 202，另一个 409，操作记录一条 |
| 再次失败后补发 | 每轮追加操作记录，保留各轮旧错误和失败次数 |

重点读 `shouldRollBackResetWhenAuditInsertFails`：

1. 准备 FAILED 任务并保存完整快照。
2. 仅给当前测试数据库增加一条约束，使指定原因的操作记录无法插入。
3. 调用容器中的事务服务。任务 UPDATE 先执行，随后操作记录 INSERT 抛出异常。
4. 在事务方法退出后重新查库，整条任务应与快照相同，且操作记录数为零。

如果服务缺少 `@Transactional`，前面的 UPDATE 会独立提交，这个测试就会失败。[Spring 事务代理说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)

单独运行补发测试或回滚用例：

```powershell
mvn -B test "-Dtest=TaskMessageAdminTest"
mvn -B test "-Dtest=TaskMessageAdminTest#shouldRollBackResetWhenAuditInsertFails"
```

并发测试验证同 JVM 中两个请求的处理结果，不替代真实 MySQL、多进程竞争测试。MockMvc 不监听 HTTP 端口，也不连接 RocketMQ。

`OrderCreatedConsumerTest` 调用实际消息监听器与事务服务，H2 保存真实的去重记录和业务计数。只有 SDK 的 `MessageView` 使用 Mockito 提供消息内容，不创建真实消费者。

| 消费者场景 | 验证行为 |
| --- | --- |
| 首次消费 | 返回 SUCCESS，去重记录和业务计数一起提交，额外消息字段可忽略 |
| 重复投递 | 两次均返回 SUCCESS，业务只生效一次 |
| 不同任务与消费组 | 幂等键为 (consumer_group, task_id)，不同任务或其他消费组的记录分别处理 |
| 并发重复 | 两个测试线程同时消费相同任务，业务计数与当前组去重记录均为一 |
| 业务失败与恢复 | 返回 FAILURE，回滚本次去重记录，保留先前已提交业务；恢复后同任务可重试 |
| 业务唯一键冲突 | 业务 UPDATE 的 DuplicateKeyException 不被吞掉，监听器返回 FAILURE，去重记录回滚 |
| 非法消息字段 | taskId 和 orderNo 缺失、空白、超过长度，共六组输入均无业务写入 |
| 无法解析的正文 | 非 JSON、空正文、JSON null 和数组返回 FAILURE |
| 合法长度边界 | 长度 1 和 64 的标识均可正常消费 |

重点读 `shouldRollBackConsumeMarkerAndRetryAfterRecovery`：先完成一笔正常消费，再让统计行暂时不可用。失败消息虽然已经执行去重记录 INSERT，但后续业务失败会回滚这条记录；统计行恢复后，重投同一 taskId 仍能完成业务。再次重复投递时，计数保持不变。

只捕获去重 INSERT 的 DuplicateKeyException，才能区分“已经消费过”与“业务自身出现唯一键错误”。测试仅在独立 H2 库增加一个业务唯一约束，真实制造后者，验证其不能被错误确认为消费成功。

MessageView 每次 getBody 都返回新 ByteBuffer，避免第一次 UTF-8 解码推进位置后，让重复投递测试误读为空正文。

单独运行消费者测试或回滚用例：

```powershell
mvn -B test "-Dtest=OrderCreatedConsumerTest"
mvn -B test "-Dtest=OrderCreatedConsumerTest#shouldRollBackConsumeMarkerAndRetryAfterRecovery"
```

这里验证的是监听器返回值和数据库效果，不包含真实 Broker 的 ACK、重试调度或死信队列。[RocketMQ 消费者说明](https://rocketmq.apache.org/docs/featureBehavior/06consumertype/)

`TaskMessageQueryTest` 验证任务查询和统计接口。

| 接口 | 约定 |
| --- | --- |
| GET /task-messages?status=FAILED | 状态必填，仅支持 PENDING、PROCESSING、SENT、FAILED；按 id 降序返回最近二十条匹配任务 |
| GET /task-messages/stats | 返回四个大写状态键及各自数量，没有记录的状态仍返回零 |

查询视图字段为 taskId、bizId、topic、status、retryCount、nextRetryTime、lockedUntil、lastError、updateTime。lockedUntil 可以为 null；不返回消息正文和抢占令牌。

14 项测试覆盖状态过滤、排序、数量上限、时间字段、非法参数、空表统计和缺失状态补零。

```powershell
mvn -B test "-Dtest=TaskMessageQueryTest"
```

实现统计时，先准备四个状态的零值，再用一条 GROUP BY 查询填入实际数量。用枚举接收 status 请求参数，可以把非法输入在进入服务前转换为 400 响应。[Spring 参数类型转换](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/typeconversion.html)

游标分页练习使用 `GET /task-messages/page`。`status` 必填，`limit` 默认为 20，允许 1 到 100。第一页省略 `beforeId`，下一页传入上次响应的 `nextBeforeId`。响应包含 `items` 和 `nextBeforeId`。游标为十进制字符串，最后一页返回 `null`。

通过类名单独运行分页验收。

```powershell
mvn -B test "-Dtest=TaskMessagePageAcceptance"
```

接口实现前，12 项用例会因 HTTP 404 失败。实现后应全部通过，覆盖跨页顺序、翻页期间插入新任务、空页、大整数游标和参数校验。

在已配置 MySQL 环境变量和 MQ 连接的终端运行默认回归。

```powershell
mvn -B test
```

默认回归共执行 85 次，包括完整应用启动测试 `LocalTaskMessageLearningApplicationTests`。在 PowerShell 中运行时，在当前终端提供 `DB_USERNAME`、`DB_PASSWORD`。
