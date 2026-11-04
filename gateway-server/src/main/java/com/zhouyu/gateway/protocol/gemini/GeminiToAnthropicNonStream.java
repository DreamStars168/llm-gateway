package com.zhouyu.gateway.protocol.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Gemini 非流式响应 → Anthropic Messages 响应。
 *
 * <p>用于「Anthropic 客户端 + Gemini 渠道 + 非流式」的组合。
 */
public final class GeminiToAnthropicNonStream {

    private GeminiToAnthropicNonStream() {
    }

    public static byte[] convert(ObjectMapper mapper, byte[] geminiBody, String requestedModel) {
        try {
            JsonNode root = mapper.readTree(geminiBody);

            if (root.has("error")) {
                return geminiBody;
            }

            JsonNode cand = root.path("candidates").path(0);
            JsonNode parts = cand.path("content").path("parts");

            ObjectNode out = mapper.createObjectNode();
            out.put("id", "msg_gw_" + Long.toHexString(System.nanoTime()));
            out.put("type", "message");
            out.put("role", "assistant");
            out.put("model", requestedModel != null ? requestedModel : "gemini");

            ArrayNode content = out.putArray("content");
            if (parts.isArray()) {
                for (JsonNode p : parts) {
                    if (p.path("thought").asBoolean(false)) {
                        String t = p.path("text").asText("");
                        if (!t.isEmpty()) {
                            ObjectNode b = content.addObject();
                            b.put("type", "thinking");
                            b.put("thinking", t);
                        }
                        continue;
                    }
                    JsonNode fc = p.get("functionCall");
                    if (fc != null && !fc.isNull()) {
                        ObjectNode b = content.addObject();
                        b.put("type", "tool_use");
                        b.put("id", "toolu_" + Long.toHexString(System.nanoTime()));
                        b.put("name", fc.path("name").asText(""));
                        JsonNode args = fc.get("args");
                        b.set("input", args == null ? mapper.createObjectNode() : args);
                        continue;
                    }
                    String text = p.path("text").asText("");
                    if (!text.isEmpty()) {
                        ObjectNode b = content.addObject();
                        b.put("type", "text");
                        b.put("text", text);
                    }
                }
            }

            String finish = cand.path("finishReason").asText("STOP");
            out.put("stop_reason", switch (finish) {
                case "MAX_TOKENS" -> "max_tokens";
                case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "end_turn";
                default -> "end_turn";
            });
            out.putNull("stop_sequence");

            JsonNode usage = root.get("usageMetadata");
            ObjectNode u = out.putObject("usage");
            u.put("input_tokens", usage == null ? 0 : usage.path("promptTokenCount").asInt(0));
            u.put("output_tokens", usage == null ? 0 : usage.path("candidatesTokenCount").asInt(0));

            return mapper.writeValueAsBytes(out);
        } catch (Exception e) {
            return geminiBody;
        }
    }
}
