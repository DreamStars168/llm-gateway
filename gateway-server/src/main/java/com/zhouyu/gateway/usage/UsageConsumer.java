package com.zhouyu.gateway.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 用量消费者。
 *
 * <p>幂等策略：{@code relay_log.request_id} 是唯一键，
 * 使用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 保证重复投递不产生重复扣费/重复统计。
 *
 * <p>手动 ack 由 Spring Boot 的默认 AUTO ack 简化为「方法不抛异常即 ack」；
 * 抛异常则进入死信队列，由补偿任务处理。
 */
@Component
public class UsageConsumer {

    private static final Logger log = LoggerFactory.getLogger(UsageConsumer.class);

    private final JdbcTemplate jdbc;

    public UsageConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 明细落库。返回是否「首次插入」，供聚合环节幂等判断。
     */
    @RabbitListener(queues = UsageMessaging.QUEUE_PERSIST)
    public void persist(UsageEvent e) {
        try {
            boolean firstInsert = insertRelayLog(e);
            // 只有首次插入才累加聚合，避免 MQ 重复投递导致 request_count 翻倍
            if (firstInsert) {
                upsertDaily(e);
            }
        } catch (Exception ex) {
            log.error("明细落库失败 requestId={}", e.requestId(), ex);
            throw ex;
        }
    }

    private boolean insertRelayLog(UsageEvent e) {
        String sql = """
                INSERT INTO relay_log
                  (request_id, tenant_id, api_key_id, virtual_model, upstream_model, channel_id, channel_name,
                   protocol_in, protocol_out, gateway_stream, status_code, error_code,
                   input_tokens, output_tokens, cached_tokens, reasoning_tokens, usage_source, cost_micro,
                   ttft_ms, total_ms, retry_count, sticky_hit, route_trace, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try {
            jdbc.update(sql,
                    e.requestId(), e.tenantId(), e.apiKeyId(), e.virtualModel(), e.upstreamModel(),
                    e.channelId(), e.channelName(), e.protocolIn(), e.protocolOut(), e.streaming(),
                    e.statusCode(), e.errorCode(), e.inputTokens(), e.outputTokens(),
                    e.cachedTokens(), e.reasoningTokens(), e.usageSource(), e.costMicro(),
                    e.ttftMs(), e.totalMs(), e.retryCount(), e.stickyHit(), e.routeTrace(),
                    new java.sql.Timestamp(e.createdAt()));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 重复投递，跳过（幂等）
            return false;
        }
    }

    /**
     * 聚合队列：作为独立消费者存在，用于「只关心聚合、不关心明细」的部署形态。
     *
     * <p>默认部署下聚合已由 {@link #persist} 在同一次事务语义内完成；
     * 为避免重复累加，本方法默认<b>不启用</b>（见 application.yml 中
     * {@code gateway.usage.aggregate-consumer-enabled}）。开启时会先检查明细是否存在。
     */
    @RabbitListener(queues = UsageMessaging.QUEUE_AGGREGATE, autoStartup = "${gateway.usage.aggregate-consumer-enabled:false}")
    public void aggregate(UsageEvent e) {
        try {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM relay_log WHERE request_id = ?", Integer.class, e.requestId());
            if (exists != null && exists > 0) {
                // 明细已由 persist 处理并累加过，跳过
                return;
            }
            upsertDaily(e);
        } catch (Exception ex) {
            log.error("聚合失败 requestId={}", e.requestId(), ex);
            throw ex;
        }
    }

    private void upsertDaily(UsageEvent e) {
        LocalDate date = Instant.ofEpochMilli(e.createdAt())
                .atZone(ZoneId.systemDefault()).toLocalDate();
        String sql = """
                INSERT INTO usage_daily
                  (stat_date, tenant_id, virtual_model, channel_id, request_count, error_count,
                   input_tokens, output_tokens, cached_tokens, cost_micro, ttft_sum_ms, total_sum_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  request_count = request_count + 1,
                  error_count = error_count + VALUES(error_count),
                  input_tokens = input_tokens + VALUES(input_tokens),
                  output_tokens = output_tokens + VALUES(output_tokens),
                  cached_tokens = cached_tokens + VALUES(cached_tokens),
                  cost_micro = cost_micro + VALUES(cost_micro),
                  ttft_sum_ms = ttft_sum_ms + VALUES(ttft_sum_ms),
                  total_sum_ms = total_sum_ms + VALUES(total_sum_ms)
                """;
        jdbc.update(sql, java.sql.Date.valueOf(date), e.tenantId(), e.virtualModel(), e.channelId(),
                1L, e.statusCode() >= 400 ? 1L : 0L, e.inputTokens(), e.outputTokens(),
                e.cachedTokens(), e.costMicro(), e.ttftMs(), e.totalMs());
    }

    /**
     * 账单明细。
     */
    @RabbitListener(queues = UsageMessaging.QUEUE_BILLING)
    public void bill(UsageEvent e) {
        if (e.costMicro() <= 0) {
            return;
        }
        try {
            String sql = """
                    INSERT INTO bill_item (bill_no, tenant_id, request_id, virtual_model, channel_id, amount_micro, usage_source, created_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE amount_micro = VALUES(amount_micro)
                    """;
            jdbc.update(sql,
                    "BILL-" + Instant.ofEpochMilli(e.createdAt()).atZone(ZoneId.systemDefault()).toLocalDate(),
                    e.tenantId(), e.requestId(), e.virtualModel(), e.channelId(), e.costMicro(),
                    e.usageSource(), new java.sql.Timestamp(e.createdAt()));
        } catch (Exception ex) {
            log.error("账单写入失败 requestId={}", e.requestId(), ex);
            throw ex;
        }
    }
}



