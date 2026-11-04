package com.zhouyu.gateway.protocol.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhouyu.gateway.protocol.canonical.CanonicalChunk;

/**
 * CanonicalChunk → OpenAI Chat SSE 数据帧。
 *
 * <p><b>有状态</b>：维护响应 id/created 与首帧标记（首帧必须带 role）。
 */
public class OpenAiChatSseEncoder {

    private final ObjectMapper mapper;
    private final String id;
    private final long created;
    private boolean firstDeltaSent = false;
    private String model = "unknown";
    private int toolIndexSeq = 0;
    private int pendingIn = 0;
    private int pendingOut = 0;

    public OpenAiChatSseEncoder(ObjectMapper mapper) {
        this.mapper = mapper;
        this.id = "chatcmpl-gw-" + Long.toHexString(System.nanoTime());
        this.created = System.currentTimeMillis() / 1000;
    }

    public String encode(CanonicalChunk chunk) {
        if (chunk instanceof CanonicalChunk.Start s) {
            if (s.model() != null) {
                model = s.model();
            }
            return "";
        }

        if (chunk instanceof CanonicalChunk.TextDelta d) {
            return deltaFrame(d.text(), null, null, null);
        }

        if (chunk instanceof CanonicalChunk.ThinkingDelta d) {
            return deltaFrame(null, d.text(), null, null);
        }

        if (chunk instanceof CanonicalChunk.ToolCallStart t) {
            ObjectNode root = baseFrame();
            var choice = root.putArray("choices").addObject();
            choice.put("index", 0);
            var delta = choice.putObject("delta");
            if (!firstDeltaSent) {
                delta.put("role", "assistant");
                firstDeltaSent = true;
            }
            var calls = delta.putArray("tool_calls");
            var call = calls.addObject();
            call.put("index", t.index() >= 0 ? t.index() : toolIndexSeq++);
            call.put("id", t.id());
            call.put("type", "function");
            var fn = call.putObject("function");
            fn.put("name", t.name());
            fn.put("arguments", "");
            choice.putNull("finish_reason");
            return frame(root);
        }

        if (chunk instanceof CanonicalChunk.ToolCallDelta d) {
            ObjectNode root = baseFrame();
            var choice = root.putArray("choices").addObject();
            choice.put("index", 0);
            var delta = choice.putObject("delta");
            var calls = delta.putArray("tool_calls");
            var call = calls.addObject();
            call.put("index", d.index());
            var fn = call.putObject("function");
            fn.put("arguments", d.partialJson());
            choice.putNull("finish_reason");
            return frame(root);
        }

        if (chunk instanceof CanonicalChunk.ToolCallEnd) {
            return "";
        }

        if (chunk instanceof CanonicalChunk.UsageChunk u) {
            pendingIn = u.inputTokens();
            pendingOut = u.outputTokens();
            return "";
        }

        if (chunk instanceof CanonicalChunk.Finish f) {
            ObjectNode root = baseFrame();
            var choice = root.putArray("choices").addObject();
            choice.put("index", 0);
            choice.putObject("delta");
            choice.put("finish_reason", mapFinish(f.stopReason()));

            ObjectNode usage = root.putObject("usage");
            usage.put("prompt_tokens", pendingIn);
            usage.put("completion_tokens", pendingOut);
            usage.put("total_tokens", pendingIn + pendingOut);
            return frame(root);
        }

        if (chunk instanceof CanonicalChunk.ErrorChunk e) {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode err = root.putObject("error");
            err.put("message", e.message());
            err.put("type", e.type());
            return frame(root);
        }

        return "";
    }

    private String deltaFrame(String content, String reasoning, String finish, String role) {
        ObjectNode root = baseFrame();
        var choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        var delta = choice.putObject("delta");
        if (!firstDeltaSent) {
            delta.put("role", "assistant");
            firstDeltaSent = true;
        }
        if (content != null) {
            delta.put("content", content);
        }
        if (reasoning != null) {
            delta.put("reasoning_content", reasoning);
        }
        if (finish != null) {
            choice.put("finish_reason", finish);
        } else {
            choice.putNull("finish_reason");
        }
        return frame(root);
    }

    private ObjectNode baseFrame() {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", id);
        root.put("object", "chat.completion.chunk");
        root.put("created", created);
        root.put("model", model);
        return root;
    }

    private String frame(ObjectNode node) {
        try {
            return "data: " + mapper.writeValueAsString(node) + "\n\n";
        } catch (Exception e) {
            return "";
        }
    }

    static String mapFinish(String canonical) {
        if (canonical == null) {
            return "stop";
        }
        return switch (canonical) {
            case "max_tokens" -> "length";
            case "tool_use", "tool_calls" -> "tool_calls";
            case "content_filter" -> "content_filter";
            default -> "stop";
        };
    }
}
