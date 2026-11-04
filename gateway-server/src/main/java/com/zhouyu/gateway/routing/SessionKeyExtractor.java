package com.zhouyu.gateway.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.routing.RouteTable.Candidate;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 会话标识提取。
 *
 * <p>不同客户端把会话 ID 放在不同位置，按优先级依次尝试。
 * 这套顺序是实战经验（参考 cc-router 的做法）：
 * <ul>
 *   <li>Claude Code：{@code x-claude-code-session-id} → {@code metadata.user_id}</li>
 *   <li>Codex：{@code prompt_cache_key} → {@code session_id} 头</li>
 *   <li>OpenAI 客户端：body {@code user} → {@code x-session-id} 头</li>
 *   <li>兜底：首条 user 消息内容的 hash（同一问题的重复请求会命中，但不理想）</li>
 * </ul>
 *
 * <p>为什么要按 body 兜底：很多客户端什么都不带，但同一会话的首条消息相同，
 * 用内容 hash 至少能保住首轮缓存；后续轮次内容变化会重新分配，属于可接受的降级。
 */
@Component
public class SessionKeyExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 提取会话键。返回 null 表示无法识别，调用方应退化为加权轮询。
     */
    public String extract(ServerHttpRequest request, byte[] body) {
        // 1. Claude Code 专用头
        String v = request.getHeaders().getFirst("x-claude-code-session-id");
        if (notBlank(v)) {
            return "cc:" + v.trim();
        }

        // 2. Codex
        v = request.getHeaders().getFirst("session_id");
        if (notBlank(v)) {
            return "codex:" + v.trim();
        }

        // 3. 通用会话头
        v = request.getHeaders().getFirst("x-session-id");
        if (notBlank(v)) {
            return "sid:" + v.trim();
        }

        // 4. 从 body 里找
        try {
            JsonNode root = MAPPER.readTree(body);

            // Codex 的 prompt_cache_key
            JsonNode pck = root.get("prompt_cache_key");
            if (pck != null && pck.isTextual() && notBlank(pck.asText())) {
                return "pck:" + pck.asText().trim();
            }

            // OpenAI 的 user 字段
            JsonNode user = root.get("user");
            if (user != null && user.isTextual() && notBlank(user.asText())) {
                return "user:" + user.asText().trim();
            }

            // Anthropic 的 metadata.user_id
            JsonNode metadata = root.get("metadata");
            if (metadata != null) {
                JsonNode uid = metadata.get("user_id");
                if (uid != null && uid.isTextual() && notBlank(uid.asText())) {
                    return "uid:" + uid.asText().trim();
                }
            }

            // 兜底：首条消息内容 hash
            JsonNode messages = root.get("messages");
            if (messages != null && messages.isArray() && !messages.isEmpty()) {
                JsonNode first = messages.get(0);
                JsonNode content = first.get("content");
                String text = content == null ? "" : (content.isTextual() ? content.asText() : content.toString());
                if (notBlank(text)) {
                    return "hash:" + sha256(text).substring(0, 16);
                }
            }
        } catch (Exception ignored) {
            // 解析失败则不提供会话键
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
