package com.zhouyu.gateway.protocol.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhouyu.gateway.protocol.canonical.CanonicalRequest;
import com.zhouyu.gateway.protocol.canonical.ContentPart;
import com.zhouyu.gateway.protocol.canonical.Message;
import com.zhouyu.gateway.protocol.canonical.ToolDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CanonicalRequest → OpenAI Chat Completions 报文。
 *
 * <p>关键转换规则：
 * <ul>
 *   <li>Anthropic 顶层 system → messages 首条 system 消息</li>
 *   <li>tool_result（属于 user 消息）→ 独立的 role=tool 消息</li>
 *   <li>tool_use → assistant.tool_calls</li>
 *   <li>thinking → 丢弃（OpenAI Chat 无对应字段，除非目标支持 reasoning_content）</li>
 * </ul>
 */
@Component
public class OpenAiChatRequestBuilder {

    private final ObjectMapper mapper;

    public OpenAiChatRequestBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] build(CanonicalRequest req, String upstreamModel) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", upstreamModel);

        ArrayNode messages = root.putArray("messages");

        if (req.system() != null && !req.system().isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", req.system());
        }

        for (Message m : req.messages()) {
            appendMessage(messages, m);
        }

        if (!req.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolDef t : req.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode fn = tool.putObject("function");
                fn.put("name", t.name());
                if (t.description() != null) {
                    fn.put("description", t.description());
                }
                if (t.inputSchema() != null) {
                    fn.set("parameters", t.inputSchema());
                } else {
                    ObjectNode empty = fn.putObject("parameters");
                    empty.put("type", "object");
                    empty.putArray("properties");
                }
            }
        }

        ToolDef.ToolChoice tc = req.toolChoice();
        if (tc != null) {
            switch (tc.mode()) {
                case "required" -> root.put("tool_choice", "required");
                case "none" -> root.put("tool_choice", "none");
                case "tool" -> {
                    ObjectNode choice = root.putObject("tool_choice");
                    choice.put("type", "function");
                    choice.putObject("function").put("name", tc.toolName());
                }
                default -> root.put("tool_choice", "auto");
            }
        }

        var p = req.params();
        if (p.temperature() != null) root.put("temperature", p.temperature());
        if (p.topP() != null) root.put("top_p", p.topP());
        if (p.maxTokens() != null) root.put("max_tokens", p.maxTokens());
        if (p.stop() != null && !p.stop().isEmpty()) {
            ArrayNode stop = root.putArray("stop");
            p.stop().forEach(stop::add);
        }
        if (p.presencePenalty() != null) root.put("presence_penalty", p.presencePenalty());
        if (p.frequencyPenalty() != null) root.put("frequency_penalty", p.frequencyPenalty());
        if (p.seed() != null) root.put("seed", p.seed());

        if (req.reasoning().enabled()) {
            root.put("reasoning_effort", req.reasoning().toEffort());
        }

        root.put("stream", req.stream());
        if (req.stream()) {
            // 强制上游在最后一帧带 usage，否则流式场景拿不到计费数据
            ObjectNode so = root.putObject("stream_options");
            so.put("include_usage", true);
        }

        try {
            return mapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 OpenAI 请求失败", e);
        }
    }

    /**
     * 单条 IR 消息 → 1..N 条 OpenAI 消息。
     * tool_result 必须拆成独立的 role=tool 消息，因此可能产生多条。
     */
    private void appendMessage(ArrayNode messages, Message m) {
        List<ContentPart> toolResults = new ArrayList<>();
        List<ContentPart> others = new ArrayList<>();
        for (ContentPart p : m.parts()) {
            if (p instanceof ContentPart.ToolResultPart) {
                toolResults.add(p);
            } else {
                others.add(p);
            }
        }

        if (!toolResults.isEmpty()) {
            for (ContentPart p : toolResults) {
                ContentPart.ToolResultPart tr = (ContentPart.ToolResultPart) p;
                ObjectNode msg = messages.addObject();
                msg.put("role", "tool");
                msg.put("tool_call_id", tr.toolUseId());
                msg.put("content", flattenText(tr.content()));
            }
            // 若同一消息里还有普通内容，单独再发一条
            if (!others.isEmpty()) {
                appendContentMessage(messages, m.role(), others);
            }
            return;
        }

        appendContentMessage(messages, m.role(), others);
    }

    private void appendContentMessage(ArrayNode messages, Message.Role role, List<ContentPart> parts) {
        if (parts.isEmpty()) {
            return;
        }

        List<ContentPart.ToolUsePart> toolUses = new ArrayList<>();
        List<ContentPart> contentParts = new ArrayList<>();
        for (ContentPart p : parts) {
            if (p instanceof ContentPart.ToolUsePart tu) {
                toolUses.add(tu);
            } else {
                contentParts.add(p);
            }
        }

        ObjectNode msg = messages.addObject();
        msg.put("role", role.wire());

        boolean hasImage = contentParts.stream().anyMatch(p -> p instanceof ContentPart.ImagePart);
        if (hasImage) {
            ArrayNode arr = msg.putArray("content");
            for (ContentPart p : contentParts) {
                if (p instanceof ContentPart.ImagePart img) {
                    ObjectNode part = arr.addObject();
                    part.put("type", "image_url");
                    ObjectNode iu = part.putObject("image_url");
                    iu.put("url", img.isUrl() ? img.data() : "data:" + img.mediaType() + ";base64," + img.data());
                } else if (p instanceof ContentPart.TextPart t) {
                    ObjectNode part = arr.addObject();
                    part.put("type", "text");
                    part.put("text", t.text());
                }
            }
        } else {
            String text = flattenText(contentParts);
            if (text == null || text.isEmpty()) {
                if (toolUses.isEmpty()) {
                    msg.put("content", "");
                } else {
                    msg.putNull("content");
                }
            } else {
                msg.put("content", text);
            }
        }

        if (!toolUses.isEmpty()) {
            ArrayNode calls = msg.putArray("tool_calls");
            for (ContentPart.ToolUsePart tu : toolUses) {
                ObjectNode call = calls.addObject();
                call.put("id", tu.id());
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", tu.name());
                try {
                    fn.put("arguments", tu.input() == null ? "{}" : mapper.writeValueAsString(tu.input()));
                } catch (Exception e) {
                    fn.put("arguments", "{}");
                }
            }
        }
    }

    private static String flattenText(List<ContentPart> parts) {
        StringBuilder sb = new StringBuilder();
        for (ContentPart p : parts) {
            if (p instanceof ContentPart.TextPart t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
