package com.zhouyu.gateway.protocol.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.protocol.canonical.CanonicalRequest;
import com.zhouyu.gateway.protocol.canonical.ContentPart;
import com.zhouyu.gateway.protocol.canonical.GenerationParams;
import com.zhouyu.gateway.protocol.canonical.Message;
import com.zhouyu.gateway.protocol.canonical.ReasoningConfig;
import com.zhouyu.gateway.protocol.canonical.ToolDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Anthropic Messages → CanonicalRequest。
 *
 * <p>设计取舍：解析只做「路由与计费必需」的部分，图片等大字段保留原始 base64 引用，
 * 不做解码/重编码，避免无谓的内存拷贝。
 */
@Component
public class AnthropicParser {

    private final ObjectMapper mapper;

    public AnthropicParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CanonicalRequest parse(byte[] body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "请求体不是合法 JSON");
        }
        if (!root.isObject()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "请求体必须是 JSON 对象");
        }

        String model = text(root, "model");
        if (model == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "缺少 model 字段");
        }

        List<String> dropped = new ArrayList<>();
        String system = extractSystem(root);

        List<Message> messages = new ArrayList<>();
        JsonNode msgs = root.get("messages");
        if (msgs == null || !msgs.isArray()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "messages 必须是数组");
        }
        for (JsonNode m : msgs) {
            messages.add(parseMessage(m, dropped));
        }

        List<ToolDef> tools = parseTools(root);
        ToolDef.ToolChoice toolChoice = parseToolChoice(root);

        GenerationParams params = new GenerationParams(
                dbl(root, "temperature"),
                dbl(root, "top_p"),
                intOrNull(root, "top_k"),
                intOrNull(root, "max_tokens"),
                strList(root, "stop_sequences"),
                null, null, null);

        ReasoningConfig reasoning = parseReasoning(root);

        return CanonicalRequest.builder()
                .requestId(null)
                .ingressProtocol(CanonicalRequest.Protocol.ANTHROPIC)
                .requestedModel(model)
                .virtualModel(model)
                .system(system)
                .messages(messages)
                .tools(tools)
                .toolChoice(toolChoice)
                .params(params)
                .reasoning(reasoning)
                .stream(root.path("stream").asBoolean(false))
                .droppedFields(dropped)
                .build();
    }

    private String extractSystem(JsonNode root) {
        JsonNode system = root.get("system");
        if (system == null || system.isNull()) {
            return null;
        }
        if (system.isTextual()) {
            return system.asText();
        }
        if (system.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : system) {
                if (block.has("text")) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(block.get("text").asText());
                }
            }
            return sb.toString();
        }
        return null;
    }

    private Message parseMessage(JsonNode m, List<String> dropped) {
        String role = text(m, "role");
        Message.Role r = "assistant".equals(role) ? Message.Role.ASSISTANT : Message.Role.USER;

        JsonNode content = m.get("content");
        List<ContentPart> parts = new ArrayList<>();

        if (content == null) {
            return new Message(r, parts);
        }
        if (content.isTextual()) {
            parts.add(ContentPart.TextPart.of(content.asText()));
            return new Message(r, parts);
        }
        if (!content.isArray()) {
            return new Message(r, parts);
        }

        for (JsonNode block : content) {
            String type = text(block, "type");
            if (type == null) {
                continue;
            }
            switch (type) {
                case "text" -> parts.add(new ContentPart.TextPart(
                        block.path("text").asText(""),
                        block.has("cache_control") ? "ephemeral" : null));
                case "image" -> parts.add(parseImage(block));
                case "tool_use" -> parts.add(new ContentPart.ToolUsePart(
                        text(block, "id"), text(block, "name"), block.get("input")));
                case "tool_result" -> parts.add(parseToolResult(block, dropped));
                case "thinking" -> parts.add(new ContentPart.ThinkingPart(
                        block.path("thinking").asText(""), text(block, "signature")));
                case "document" -> parts.add(parseDocument(block));
                default -> dropped.add("content_block:" + type);
            }
        }
        return new Message(r, parts);
    }

    private ContentPart parseImage(JsonNode block) {
        JsonNode source = block.get("source");
        if (source == null) {
            return ContentPart.ImagePart.url("");
        }
        String type = text(source, "type");
        if ("base64".equals(type)) {
            return ContentPart.ImagePart.base64(text(source, "media_type"), text(source, "data"));
        }
        if ("url".equals(type)) {
            return ContentPart.ImagePart.url(text(source, "url"));
        }
        return ContentPart.ImagePart.url("");
    }

    private ContentPart parseToolResult(JsonNode block, List<String> dropped) {
        String toolUseId = text(block, "tool_use_id");
        boolean isError = block.path("is_error").asBoolean(false);
        JsonNode content = block.get("content");
        List<ContentPart> parts = new ArrayList<>();
        if (content == null || content.isNull()) {
            // 无内容
        } else if (content.isTextual()) {
            parts.add(ContentPart.TextPart.of(content.asText()));
        } else if (content.isArray()) {
            for (JsonNode c : content) {
                if ("text".equals(text(c, "type"))) {
                    parts.add(ContentPart.TextPart.of(c.path("text").asText("")));
                } else if ("image".equals(text(c, "type"))) {
                    parts.add(parseImage(c));
                } else {
                    dropped.add("tool_result_block:" + text(c, "type"));
                }
            }
        }
        return new ContentPart.ToolResultPart(toolUseId, parts, isError);
    }

    private ContentPart parseDocument(JsonNode block) {
        JsonNode source = block.get("source");
        String mediaType = source != null ? text(source, "media_type") : null;
        String data = source != null ? text(source, "data") : null;
        return new ContentPart.DocumentPart(mediaType, data, text(block, "title"));
    }

    private List<ToolDef> parseTools(JsonNode root) {
        JsonNode tools = root.get("tools");
        if (tools == null || !tools.isArray()) {
            return List.of();
        }
        List<ToolDef> result = new ArrayList<>();
        for (JsonNode t : tools) {
            result.add(new ToolDef(text(t, "name"), text(t, "description"), t.get("input_schema")));
        }
        return result;
    }

    private ToolDef.ToolChoice parseToolChoice(JsonNode root) {
        JsonNode tc = root.get("tool_choice");
        if (tc == null || tc.isNull()) {
            return null;
        }
        String type = text(tc, "type");
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "any" -> ToolDef.ToolChoice.required();
            case "tool" -> ToolDef.ToolChoice.of(text(tc, "name"));
            case "none" -> ToolDef.ToolChoice.none();
            default -> ToolDef.ToolChoice.auto();
        };
    }

    private ReasoningConfig parseReasoning(JsonNode root) {
        JsonNode thinking = root.get("thinking");
        if (thinking == null || thinking.isNull()) {
            return ReasoningConfig.disabled();
        }
        String type = text(thinking, "type");
        if (!"enabled".equals(type)) {
            return ReasoningConfig.disabled();
        }
        return new ReasoningConfig(true, intOrNull(thinking, "budget_tokens"), null);
    }

    // ---- 小工具 ----

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Double dbl(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asDouble();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    private static List<String> strList(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (JsonNode n : v) {
            list.add(n.asText());
        }
        return list;
    }
}
