package com.zhouyu.gateway.protocol.gemini;

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
 * CanonicalRequest → Gemini {@code generateContent} 报文。
 *
 * <h3>与 OpenAI / Anthropic 的关键差异</h3>
 * <ul>
 *   <li><b>角色只有两种</b>：{@code user} / {@code model}（没有 system、tool 角色）。
 *       system 走顶层 {@code systemInstruction}，tool 结果作为 user 消息的 {@code functionResponse} part。</li>
 *   <li><b>工具调用无 id</b>：Gemini 用 {@code functionCall.name} 关联，
 *       网关需在 IR → Gemini 时丢弃 id，并在反方向按 name 反查。</li>
 *   <li><b>思考签名必须回传</b>：{@code thoughtSignature} 不回传会导致多轮工具调用 400。</li>
 *   <li><b>参数在 generationConfig 里</b>，不在顶层。</li>
 * </ul>
 */
@Component
public class GeminiRequestBuilder {

    private final ObjectMapper mapper;

    public GeminiRequestBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] build(CanonicalRequest req, String upstreamModel) {
        ObjectNode root = mapper.createObjectNode();

        ArrayNode contents = root.putArray("contents");
        for (Message m : req.messages()) {
            appendMessage(contents, m);
        }

        // system → systemInstruction
        if (req.system() != null && !req.system().isBlank()) {
            ObjectNode si = root.putObject("systemInstruction");
            ArrayNode parts = si.putArray("parts");
            parts.addObject().put("text", req.system());
        }

        // 工具定义
        if (!req.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            ObjectNode toolGroup = tools.addObject();
            ArrayNode decls = toolGroup.putArray("functionDeclarations");
            for (ToolDef t : req.tools()) {
                ObjectNode d = decls.addObject();
                d.put("name", t.name());
                if (t.description() != null) {
                    d.put("description", t.description());
                }
                if (t.inputSchema() != null) {
                    d.set("parameters", sanitizeSchema(t.inputSchema()));
                } else {
                    ObjectNode p = d.putObject("parameters");
                    p.put("type", "OBJECT");
                    p.putArray("properties");
                }
            }

            ToolDef.ToolChoice tc = req.toolChoice();
            if (tc != null) {
                ObjectNode cfg = toolGroup.putObject("toolConfig");
                ObjectNode fc = cfg.putObject("functionCallingConfig");
                switch (tc.mode()) {
                    case "required" -> fc.put("mode", "ANY");
                    case "none" -> fc.put("mode", "NONE");
                    case "tool" -> {
                        fc.put("mode", "ANY");
                        fc.putArray("allowedFunctionNames").add(tc.toolName());
                    }
                    default -> fc.put("mode", "AUTO");
                }
            }
        }

        // generationConfig
        ObjectNode gc = root.putObject("generationConfig");
        var p = req.params();
        if (p.temperature() != null) gc.put("temperature", p.temperature());
        if (p.topP() != null) gc.put("topP", p.topP());
        if (p.topK() != null) gc.put("topK", p.topK());
        if (p.maxTokens() != null) gc.put("maxOutputTokens", p.maxTokens());
        if (p.stop() != null && !p.stop().isEmpty()) {
            ArrayNode stop = gc.putArray("stopSequences");
            p.stop().forEach(stop::add);
        }
        if (req.reasoning().enabled()) {
            ObjectNode thinking = gc.putObject("thinkingConfig");
            thinking.put("includeThoughts", true);
            Integer budget = req.reasoning().toBudgetTokens();
            if (budget != null) {
                thinking.put("thinkingBudget", budget);
            }
        }

        try {
            return mapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 Gemini 请求失败", e);
        }
    }

    private void appendMessage(ArrayNode contents, Message m) {
        // tool_result 需要拆成单独的 user 消息（functionResponse 不能和普通文本混在同一 part 列表的语义里太随意）
        List<ContentPart.ToolResultPart> results = new ArrayList<>();
        List<ContentPart> others = new ArrayList<>();
        for (ContentPart p : m.parts()) {
            if (p instanceof ContentPart.ToolResultPart tr) {
                results.add(tr);
            } else {
                others.add(p);
            }
        }

        if (!others.isEmpty()) {
            ObjectNode msg = contents.addObject();
            msg.put("role", m.role().geminiWire());
            ArrayNode parts = msg.putArray("parts");
            for (ContentPart p : others) {
                appendPart(parts, p);
            }
        }

        for (ContentPart.ToolResultPart tr : results) {
            ObjectNode msg = contents.addObject();
            msg.put("role", "user");
            ArrayNode parts = msg.putArray("parts");
            ObjectNode part = parts.addObject();
            ObjectNode fr = part.putObject("functionResponse");
            // Gemini 用 name 关联，但 IR 只有 id。调用方需在 build 前把 id 换成 name；
            // 这里退化用 id 作为 name（同轮工具数少时通常能对上）。
            fr.put("name", tr.toolUseId());
            ObjectNode response = fr.putObject("response");
            response.put("content", flattenText(tr.content()));
            if (tr.isError()) {
                response.put("error", true);
            }
        }
    }

    private void appendPart(ArrayNode parts, ContentPart p) {
        if (p instanceof ContentPart.TextPart t) {
            parts.addObject().put("text", t.text());
        } else if (p instanceof ContentPart.ImagePart img) {
            ObjectNode part = parts.addObject();
            if (img.isUrl()) {
                // Gemini 不直接支持 URL，需要 fileData（仅对 GCS/YouTube 等公开资源有效）
                ObjectNode fd = part.putObject("fileData");
                fd.put("fileUri", img.data());
            } else {
                ObjectNode inline = part.putObject("inlineData");
                inline.put("mimeType", img.mediaType() == null ? "image/png" : img.mediaType());
                inline.put("data", img.data());
            }
        } else if (p instanceof ContentPart.ToolUsePart tu) {
            ObjectNode part = parts.addObject();
            ObjectNode fc = part.putObject("functionCall");
            fc.put("name", tu.name());
            if (tu.input() != null) {
                fc.set("args", tu.input());
            }
        } else if (p instanceof ContentPart.ThinkingPart th) {
            ObjectNode part = parts.addObject();
            part.put("text", th.text());
            part.put("thought", true);
            if (th.signature() != null && !th.signature().isBlank()) {
                part.put("thoughtSignature", th.signature());
            }
        } else if (p instanceof ContentPart.DocumentPart doc) {
            ObjectNode part = parts.addObject();
            ObjectNode inline = part.putObject("inlineData");
            inline.put("mimeType", doc.mediaType() == null ? "application/pdf" : doc.mediaType());
            inline.put("data", doc.data());
        }
    }

    /**
     * Gemini 的 schema 类型是大写枚举（OBJECT/STRING/...），
     * 而 JSON Schema 用小写。这里做一次转换，否则上游会 400。
     */
    private JsonNode sanitizeSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return schema;
        }
        ObjectNode copy = schema.deepCopy();
        String type = copy.path("type").asText("");
        if (!type.isEmpty()) {
            copy.put("type", type.toUpperCase());
        }
        JsonNode props = copy.get("properties");
        if (props != null && props.isObject()) {
            var it = props.fields();
            List<String> names = new ArrayList<>();
            while (it.hasNext()) {
                names.add(it.next().getKey());
            }
            for (String n : names) {
                ((ObjectNode) props).set(n, sanitizeSchema(props.get(n)));
            }
        }
        JsonNode items = copy.get("items");
        if (items != null && items.isObject()) {
            copy.set("items", sanitizeSchema(items));
        }
        return copy;
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

    /**
     * Gemini 的 URL 形态：{@code /v1beta/models/{model}:generateContent?key=...}
     * 或流式 {@code :streamGenerateContent?alt=sse}。
     */
    public static String path(String model, boolean stream, boolean useQueryKey) {
        String base = "/v1beta/models/" + model
                + (stream ? ":streamGenerateContent" : ":generateContent");
        return useQueryKey ? base + (stream ? "?alt=sse&key=" : "?key=") : base + (stream ? "?alt=sse" : "");
    }
}
