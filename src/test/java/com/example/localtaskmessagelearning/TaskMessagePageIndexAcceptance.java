package com.example.localtaskmessagelearning;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class TaskMessagePageIndexAcceptance {

    private static final String PAGE_SQL = """
            SELECT id, task_id, biz_id, topic, status, retry_count,
                   next_retry_time, locked_until, last_error, update_time
            FROM task_message_page_index_lab
            WHERE status = ? AND id <= ?
            ORDER BY id DESC
            LIMIT ?
            """;
    private static final long UPPER_ID = 9000;
    private static final int FETCH_SIZE = 21;

    @Test
    void shouldHaveVisibleStatusAndIdIndexOnTaskTable() throws SQLException {
        try (Connection connection = connect()) {
            connection.setReadOnly(true);
            assertStatusIndex(jdbc(connection), "local_task_message");
        }
    }

    @Test
    void shouldComparePlansOnTemporaryData() throws SQLException {
        try (Connection connection = connect()) {
            JdbcTemplate jdbc = jdbc(connection);
            jdbc.execute("CREATE TEMPORARY TABLE task_message_page_index_lab LIKE local_task_message");
            var existingIndex = jdbc.queryForList("""
                    SHOW INDEX FROM task_message_page_index_lab WHERE Key_name = 'idx_task_status_id'
                    """);
            if (!existingIndex.isEmpty()) {
                jdbc.execute("ALTER TABLE task_message_page_index_lab DROP INDEX idx_task_status_id");
            }

            int inserted = jdbc.update("""
                    INSERT INTO task_message_page_index_lab (id, task_id, biz_id, topic, payload, status)
                    WITH RECURSIVE digit (n) AS (
                        SELECT 0 UNION ALL SELECT n + 1 FROM digit WHERE n < 9
                    ), sample (id) AS (
                        SELECT a.n * 1000 + b.n * 100 + c.n * 10 + d.n + 1
                        FROM digit a CROSS JOIN digit b CROSS JOIN digit c CROSS JOIN digit d
                    )
                    SELECT id, CONCAT('LAB-TASK-', id), CONCAT('LAB-ORDER-', id), 'order_created',
                           JSON_OBJECT('id', id), IF(MOD(id, 100) = 0, 'FAILED', 'SENT')
                    FROM sample
                    """);
            assertThat(inserted).isEqualTo(10_000);

            printPlan(jdbc, "before");
            List<Long> before = readPageIds(jdbc);

            jdbc.execute("ALTER TABLE task_message_page_index_lab ADD INDEX idx_task_status_id (status, id)");
            assertStatusIndex(jdbc, "task_message_page_index_lab");

            printPlan(jdbc, "after");
            List<Long> after = readPageIds(jdbc);

            List<Long> expected = LongStream.range(0, FETCH_SIZE)
                    .map(offset -> UPPER_ID - offset * 100)
                    .boxed()
                    .toList();
            assertThat(before).containsExactlyElementsOf(expected);
            assertThat(after).containsExactlyElementsOf(expected);
        }
    }

    private static Connection connect() throws SQLException {
        String username = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        if (username == null || password == null) {
            throw new IllegalStateException("Set DB_USERNAME and DB_PASSWORD before running the MySQL index tests");
        }
        return DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/local_task_message_learning", username, password);
    }

    private static JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    private static void assertStatusIndex(JdbcTemplate jdbc, String tableName) {
        List<IndexColumn> columns = jdbc.query("""
                SHOW INDEX FROM %s
                WHERE Key_name = 'idx_task_status_id' AND Visible = 'YES' AND Sub_part IS NULL
                """.formatted(tableName),
                (rs, rowNum) -> new IndexColumn(rs.getInt("Seq_in_index"), rs.getString("Column_name")));
        assertThat(columns).as("visible idx_task_status_id on %s", tableName)
                .containsExactlyInAnyOrder(new IndexColumn(1, "status"), new IndexColumn(2, "id"));
    }

    private static void printPlan(JdbcTemplate jdbc, String label) {
        var plan = jdbc.queryForMap("EXPLAIN FORMAT=TRADITIONAL " + PAGE_SQL, "FAILED", UPPER_ID, FETCH_SIZE);
        String tree = jdbc.queryForObject("EXPLAIN ANALYZE FORMAT=TREE " + PAGE_SQL,
                String.class, "FAILED", UPPER_ID, FETCH_SIZE);
        System.out.printf("%s: key=%s, Extra=%s%n%s%n", label, plan.get("key"), plan.get("Extra"), tree);
    }

    private static List<Long> readPageIds(JdbcTemplate jdbc) {
        return jdbc.query(PAGE_SQL, (rs, rowNum) -> rs.getLong("id"), "FAILED", UPPER_ID, FETCH_SIZE);
    }

    private record IndexColumn(int position, String name) {}
}
