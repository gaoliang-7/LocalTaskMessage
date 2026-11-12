package com.example.localtaskmessage.support;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseConnectionCheck implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseConnectionCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

        Long orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_order", Long.class);

        Long messageCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM local_task_message", Long.class);

        System.out.printf(
                "数据库=%s，订单数=%d，消息任务数=%d%n",
                database,
                orderCount,
                messageCount
        );
    }
}
