package com.example.localtaskmessage.taskmessage.admin;

import java.util.List;

/**
 * 一页消息任务，以及继续向前翻页所需的游标。
 *
 * @param items 当前页的任务，按数据库 ID 从大到小排列
 * @param nextBeforeId 下一页请求的 beforeId，无下一页时为 null；用字符串避免大整数精度丢失
 */
public record TaskMessagePage(
        List<TaskMessageView> items,
        String nextBeforeId
) {}
