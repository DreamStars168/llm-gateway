package com.zhouyu.gateway.playground;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Playground Prompt 版本归档。
 *
 * <p>用途：每次在 Playground 发送消息时自动存档（模型、参数、消息体），
 * 支持按模型过滤、命名、回填到界面、删除。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code parent_id} 形成版本链，可 diff 两次调参的差异</li>
 *   <li>{@code messages} 存原始 JSON，不做结构化解构——避免和多协议耦合</li>
 *   <li>单条超过 256KB 拒绝，防止大图片 base64 撑爆表</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/api/v1/prompts")
public class PromptVersionController {

    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PromptVersionController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record SaveRequest(
            String name,
            String model,
            JsonNode params,
            JsonNode messages,
            Long parentId,
            List<String> tags
    ) {
    }

    /**
     * 保存一次 Prompt 快照。
     */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody SaveRequest req) {
        if (req.model() == null || req.messages() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "model 与 messages 必填"));
        }
        try {
            String messagesJson = mapper.writeValueAsString(req.messages());
            if (messagesJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                return ResponseEntity.status(413).body(Map.of("error", "messages 超过 256KB 上限"));
            }
            String paramsJson = req.params() == null ? "{}" : mapper.writeValueAsString(req.params());
            String tagsJson = req.tags() == null ? "[]" : mapper.writeValueAsString(req.tags());

            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO prompt_version
                          (tenant_id, name, model, params, messages, tags, parent_id, created_by)
                        VALUES (1,?,?,?,?,?,?,1)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, req.name());
                ps.setString(2, req.model());
                ps.setString(3, paramsJson);
                ps.setString(4, messagesJson);
                ps.setString(5, tagsJson);
                if (req.parentId() == null) {
                    ps.setNull(6, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(6, req.parentId());
                }
                return ps;
            }, kh);

            long id = kh.getKey() == null ? 0 : kh.getKey().longValue();
            return ResponseEntity.ok(Map.of("id", id, "saved", true));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.ok(Map.of("saved", false, "reason", "duplicate"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 版本列表（按时间倒序）。支持按模型过滤。
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(required = false) String model) {
        int lim = Math.min(Math.max(limit, 1), 200);
        List<Map<String, Object>> rows = (model == null || model.isBlank())
                ? jdbc.queryForList("""
                    SELECT id, name, model, params, messages, tags, parent_id, created_at
                    FROM prompt_version ORDER BY id DESC LIMIT ?
                    """, lim)
                : jdbc.queryForList("""
                    SELECT id, name, model, params, messages, tags, parent_id, created_at
                    FROM prompt_version WHERE model = ? ORDER BY id DESC LIMIT ?
                    """, model, lim);

        // 大字段在列表里截断展示，详情接口返回完整内容
        List<Map<String, Object>> brief = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>(r);
            Object msgs = m.get("messages");
            if (msgs != null && msgs.toString().length() > 200) {
                m.put("messages", msgs.toString().substring(0, 200) + "...");
                m.put("truncated", true);
            }
            return m;
        }).toList();

        return ResponseEntity.ok(Map.of("total", brief.size(), "data", brief));
    }

    /**
     * 单个版本详情（完整 messages，用于回填）。
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM prompt_version WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rows.get(0));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        int n = jdbc.update("DELETE FROM prompt_version WHERE id = ?", id);
        return ResponseEntity.ok(Map.of("deleted", n));
    }
}
