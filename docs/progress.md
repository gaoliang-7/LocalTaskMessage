# 学习进度

- 第 1 步：SQL 初始化与分片升级脚本已保留；按用户要求跳过验收。
- 第 2 步：独立补偿组已通过此前真实运行验收。
- 第 3 步：首发背压和失败分支已通过此前真实运行验收。
- 第 4 步：通用发送与订单业务分离、分层分包已通过此前 41 项真实检查。
- 第 5 步：已完成并通过真实运行验收，2026-11-12。根项目 clean package 成功，31 项结构检查及 48 项真实运行检查全部通过。
- 下一步：第 6 步，整体验收与故障复盘；尚未开始。

本轮报告目录：`H:\LocalTaskMessage\test-reports\starter-implementation-20261112-8aadd1fd`。

## 第五步完成内容

- 根项目聚合两个模块：普通 Starter JAR 与订单示例可执行 JAR。
- 自动配置通过 AutoConfiguration.imports 发现，属性绑定与条件装配生效。
- 示例提供单数据源与 JDBC 事务管理器，事务增强 order=0 包住登记切面 order=10。
- 首发线程池、独立补偿组、RocketMQ Producer 由 Starter 接线；订单 Topic、正文、消费组和消费业务留在示例。
- IDEA 应用运行配置使用 order-example，已有环境变量保持一致；早期独立 MQ 演示程序和对应运行配置已清理。
- 启动、模块边界与异常定位说明已更新至 README.md 和 architecture.md。

## 实际验收

运行学习项目最终产物，使用 JDK 17.0.12、Spring Boot 4.1.2、MySQL 8.4.13 与临时 RocketMQ 5.3.2 Broker/Proxy。没有添加单元测试。

| 场景 | 结果 |
| --- | --- |
| 自动配置发现与事务顺序 | 两个配置匹配，Boot 默认事务启用配置退让，登记器为事务代理，使用宿主 HikariDataSource 与 JdbcTransactionManager |
| 正常订单 | HTTP 201，订单和任务登记，真实 Broker 回执、SENT 回写及消费成功 |
| 登记故障 | 隔离库触发器拒绝登记，HTTP 500，订单和任务均回滚 |
| 两组补偿 | 两个门牌号的直接登记任务均由 task-retry 线程发送并消费 |
| 首发背压 | 队列满时第三个任务保留 PENDING、失败计数 0、无租约；随后补偿成功 |
| 真实发送失败 | 不存在 Topic 导致 SDK 失败，计数 1、退避 10 秒、清空租约；恢复 Topic 后发送成功 |
| 失败上限和人工补发 | 第 5 次失败终止为 FAILED；HTTP 202 补发成功，审计保留此前计数和错误 |
| 已确认但回写失败 | 保留 PROCESSING、租约与失败计数 0；日志包含 Broker messageId |
| 租约过期与重复消费 | 实际等待租约到期后补发成功，同一任务幂等记录仍为一条 |
| 管理接口 | 状态统计、失败查询、游标分页、补发审计查询通过，相邻页无重复 |
| 数据与清理 | 原学习库五张表结构及完整行数据哈希一致；隔离库、验收 JVM 和临时 MQ 进程已清理 |

可复核文件：报告目录中的 build.log、static-checks.json、accepted-checks.json、starter-runtime.log、task-snapshots.json、learning-before.json、learning-after.json、verification-result.md。
