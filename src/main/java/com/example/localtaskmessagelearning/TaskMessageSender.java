package com.example.localtaskmessagelearning;

import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class TaskMessageSender {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageSender.class);

    private final JdbcTemplate jdbcTemplate;
    private final Producer producer;
    private final ClientServiceProvider provider;

    public TaskMessageSender(JdbcTemplate jdbcTemplate,
                             Producer producer,
                             ClientServiceProvider provider) {
        this.jdbcTemplate = jdbcTemplate;
        this.producer = producer;
        this.provider = provider;
    }

//    public void send(String taskId) throws ClientException {
//        List<PendingMessage> tasks = jdbcTemplate.query("""
//                SELECT topic, payload
//                FROM local_task_message
//                WHERE task_id = ? AND status = 'PENDING'
//                """,
//                (rs, rowNum) -> new PendingMessage(
//                        rs.getString("topic"), rs.getString("payload")),
//                taskId
//        );
//
//        if (tasks.isEmpty()) return;
//
//        PendingMessage task = tasks.get(0);
//
//        Message message = provider.newMessageBuilder()
//                .setTopic(task.topic())
//                .setKeys(taskId)
//                .setBody(task.payload().getBytes(StandardCharsets.UTF_8))
//                .build();
//
//        SendReceipt receipt = producer.send(message);
//
//        int updated = jdbcTemplate.update("""
//                UPDATE local_task_message
//                SET status = 'SENT', last_error = NULL,
//                    update_time = CURRENT_TIMESTAMP
//                WHERE task_id = ? AND status = 'PENDING'
//                """, taskId);
//
//        if (updated != 1) {
//            throw new IllegalStateException("消息已发送，但任务状态更新异常，taskId=" + taskId);
//        }
//
//        log.info("消息已发送并标记SENT，taskId={}，messageId={}",
//                taskId, receipt.getMessageId());
//    }

    public synchronized void send(String taskId) throws ClientException{

        List<PendingMessage> tasks = jdbcTemplate.query("""
            SELECT topic, payload
            FROM local_task_message
            WHERE task_id = ? AND status = 'PENDING'
              AND next_retry_time <= CURRENT_TIMESTAMP
            """,
                (rs, rowNum) -> new PendingMessage(
                        rs.getString("topic"), rs.getString("payload")),
                taskId
        );

        if (tasks.isEmpty()) {
            return;
        }

        PendingMessage task = tasks.get(0);

        try {
            Message message = provider.newMessageBuilder()
                    .setTopic(task.topic())
                    .setKeys(taskId)
                    .setBody(task.payload().getBytes(StandardCharsets.UTF_8))
                    .build();

            SendReceipt receipt = producer.send(message);

            int updated = jdbcTemplate.update("""
                UPDATE local_task_message
                SET status = 'SENT', last_error = NULL,
                    update_time = CURRENT_TIMESTAMP
                WHERE task_id = ? AND status = 'PENDING'
                """, taskId);

            if (updated != 1) {
                throw new IllegalStateException("消息已发送，但任务状态更新异常，taskId=" + taskId);
            }

            log.info("消息已发送并标记SENT，taskId={}，messageId={}",
                    taskId, receipt.getMessageId());

        } catch (Exception failure) {
            try {
                jdbcTemplate.update("""
                    UPDATE local_task_message
                    SET retry_count = retry_count + 1,
                        next_retry_time = TIMESTAMPADD(SECOND, 10, CURRENT_TIMESTAMP),
                        last_error = LEFT(?, 512),
                        update_time = CURRENT_TIMESTAMP
                    WHERE task_id = ? AND status = 'PENDING'
                    """, failure.toString(), taskId);
            } catch (Exception recordFailure) {
                failure.addSuppressed(recordFailure);
            }

            throw failure;
        }

    }

    private record PendingMessage(String topic, String payload){}

}
