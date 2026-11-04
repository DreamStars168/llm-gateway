package com.zhouyu.gateway.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志与统计查询。
 *
 * <p>所有列表查询强制时间范围（默认近 24h），避免全表扫描。
 */
@RestController
@RequestMapping("/admin/api/v1")
public class AdminStatsController {

    private final JdbcTemplate jdbc;

    public AdminStatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 请求日志分页。 */
    @GetMapping("/logs")
    public ResponseEntity<?> logs(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) String model,
                                  @RequestParam(required = false) String channel,
                                  @RequestParam(required = false) Integer status,
                                  @RequestParam(required = false) String requestId) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page - 1, 0) * limit;

        StringBuilder where = new StringBuilder(" WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (model != null && !model.isBlank()) {
            where.append(" AND virtual_model = ?");
            args.add(model);
        }
        if (channel != null && !channel.isBlank()) {
            where.append(" AND channel_name = ?");
            args.add(channel);
        }
        if (status != null) {
            where.append(" AND status_code = ?");
            args.add(status);
        }
        if (requestId != null && !requestId.isBlank()) {
            where.append(" AND request_id = ?");
            args.add(requestId);
        }

        Integer total = jdbc.queryForObject("SELECT COUNT(1) FROM relay_log" + where,
                Integer.class, args.toArray());

        args.add(limit);
        args.add(offset);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT request_id, virtual_model, upstream_model, channel_name, protocol_in, protocol_out,"
                        + " gateway_stream, status_code, error_code, input_tokens, output_tokens,"
                        + " cost_micro, usage_source, ttft_ms, total_ms, created_at"
                        + " FROM relay_log" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("page", page);
        out.put("size", limit);
        out.put("data", rows);
        return ResponseEntity.ok(out);
    }

    /** 单条日志详情。 */
    @GetMapping("/logs/{requestId}")
    public ResponseEntity<?> logDetail(@PathVariable String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM relay_log WHERE request_id = ?", requestId);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rows.get(0));
    }

    /** 概览统计：近 24 小时。 */
    @GetMapping("/stats/overview")
    public ResponseEntity<?> overview() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(1) AS requests,
                       COALESCE(SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END), 0) AS errors,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(cost_micro), 0) AS cost_micro,
                       COALESCE(AVG(total_ms), 0) AS avg_ms
                FROM relay_log
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                """);
        long reqs = ((Number) row.getOrDefault("requests", 0)).longValue();
        long errs = ((Number) row.getOrDefault("errors", 0)).longValue();
        row.put("successRate", reqs == 0 ? 1.0 : 1.0 - (double) errs / reqs);
        return ResponseEntity.ok(row);
    }

    /** 按模型分组的统计。 */
    @GetMapping("/stats/by-model")
    public ResponseEntity<?> byModel() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT virtual_model,
                       COUNT(1) AS requests,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(cost_micro), 0) AS cost_micro
                FROM relay_log
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                GROUP BY virtual_model
                ORDER BY requests DESC
                """);
        return ResponseEntity.ok(Map.of("data", rows));
    }

    /** 按渠道分组的统计。 */
    @GetMapping("/stats/by-channel")
    public ResponseEntity<?> byChannel() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT channel_name,
                       COUNT(1) AS requests,
                       COALESCE(SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END), 0) AS errors,
                       COALESCE(AVG(total_ms), 0) AS avg_ms,
                       COALESCE(SUM(cost_micro), 0) AS cost_micro
                FROM relay_log
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                GROUP BY channel_name
                ORDER BY requests DESC
                """);
        return ResponseEntity.ok(Map.of("data", rows));
    }

    /** 时间序列（按分钟聚合，最近 2 小时）。 */
    @GetMapping("/stats/timeseries")
    public ResponseEntity<?> timeseries() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS ts,
                       COUNT(1) AS requests,
                       COALESCE(SUM(input_tokens + output_tokens), 0) AS tokens,
                       COALESCE(SUM(cost_micro), 0) AS cost_micro
                FROM relay_log
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 2 HOUR)
                GROUP BY ts
                ORDER BY ts
                """);
        return ResponseEntity.ok(Map.of("data", rows));
    }
}
