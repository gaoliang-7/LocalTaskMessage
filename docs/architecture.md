# Starter 与订单示例

## 依赖与包归属

依赖方向为 order-example -> local-task-message-spring-boot-starter。根 POM 只聚合；Starter 是普通 JAR，示例是可执行 Spring Boot JAR。

| 所属模块与包 | 内容 |
| --- | --- |
| Starter taskmessage.api | @LocalTaskMessage、TaskMessageCommand |
| Starter taskmessage.core | 登记、发送状态机、状态、提交事件 |
| Starter taskmessage.spring | 注解切面、AFTER_COMMIT 事件监听 |
| Starter taskmessage.config | 属性绑定、首发线程池 |
| Starter taskmessage.retry | 分片扫描、专用调度器 |
| Starter taskmessage.transport | 通用发送接口、RocketMQ 适配 |
| Starter taskmessage.autoconfigure | 条件装配入口 |
| 示例 order | Topic、正文、消费组、下单与消费业务 |
| 示例 taskmessage.admin | 查询、游标分页、人工补发和审计 API |
| 示例 support | 启动检查、ping |

管理 API 留在示例，Starter 不引入 Spring MVC 或 HTTP 异常。MySQL 驱动由示例提供。

## 自动装配与事务

imports 资源声明 RocketMqTaskMessageAutoConfiguration 和 TaskMessageAutoConfiguration。前者绑定 rocketmq 属性并提供默认 Factory、Producer、Transport；后者在 JDBC 相关自动配置之后登记核心 Bean、绑定 task-message 属性并导入专用线程池和调度配置。

默认 Bean 按类型或约定名称使用 ConditionalOnMissingBean。两个专用线程池设置 defaultCandidate=false 并使用 Qualifier 注入，不成为宿主其他任务的默认线程池。

宿主提供单数据源、JdbcTemplate 和 JDBC 事务管理器。事务管理器将相同数据源的 Connection 绑定到线程，订单服务和登记器使用同一连接完成写入。Starter 不创建第二套数据源或事务管理器。

```text
OrderController
  -> 事务增强 order=0，开启事务
    -> LocalTaskMessageAspect order=10
      -> OrderService.createOrder，写订单并返回 TaskMessageCommand
      -> TaskMessageRegistrar.register，MANDATORY，写任务并发布事件
  -> 提交事务
  -> TaskMessageCreatedListener，AFTER_COMMIT
    -> 首发线程池
      -> TaskMessageSender.send
        -> 原子抢占租约
        -> TaskMessageTransport.send
        -> token + 有效租约条件回写 SENT
```

Starter 在 Boot 默认事务注解配置之前启用事务增强 order=0。业务方法、登记 SQL 或返回类型校验失败都会使事务回滚，AFTER_COMMIT 不执行。TaskMessageSender 的 NOT_SUPPORTED 还保证 CALLER_RUNS 在提交回调线程执行时不复用残留事务资源。

## 补偿与故障

retry_ready_at 为生成列：PENDING 取 next_retry_time，PROCESSING 取 locked_until，SENT/FAILED 为 NULL。补偿按 house_number 和到期时间扫描 idx_task_house_ready；每轮重新查询，没有跨轮永久递增 ID 游标。

每组登记独立 fixed-delay 任务，调度池大小等于组数；组内串行，一个组慢时其他组仍有可用线程。专用调度器不替换宿主全局 @Scheduled 的调度器。扫描只是候选，最终归属由带状态和到期条件的原子 UPDATE 决定。旧 token 不能覆盖新租约；存活实例覆盖目标门牌号后可在旧租约过期时接管。

| 分支 | 任务状态与后续 |
| --- | --- |
| 首发拒绝 DEFER_TO_RETRY | PENDING、计数不变、无租约；由补偿发送 |
| 实际发送失败且仍有租约 | 计数加一、清空租约，退避后 PENDING；达到上限 FAILED |
| Broker 确认但 SENT 回写确实失败 | 保留 PROCESSING、原计数和租约；过期补发，可能重复投递 |
| 租约失效 | 当前实例不回写其他实例持有的任务 |

发送失败 SQL 将 retry_count 递增放在 SET 最后，避免 MySQL 顺序赋值改变前面失败上限和退避计算所读的计数。消费在一个事务中先插入 (consumer_group, task_id) 唯一记录，再更新统计；重复命中唯一键时成功返回且不重复统计。

人工补发先 SELECT FOR UPDATE 锁定 FAILED 任务，状态重置与审计插入同事务提交。登记失败、重复消费和审计失败都应按相关表字段及任务 ID 定位，而不是仅凭 HTTP 状态判断 MQ 投递结果。
