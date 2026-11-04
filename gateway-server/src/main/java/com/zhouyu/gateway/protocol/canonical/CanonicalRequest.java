package com.zhouyu.gateway.protocol.canonical;

import java.util.List;
import java.util.Map;

/**
 * 规范请求（Internal Representation）。
 *
 * <p>所有入口协议先解析成本结构，所有出口协议再从本结构构造。
 * 这样 4 个入口 × 4 个出口只需写 4 + 4 个适配器，而不是 16 个转换器。
 *
 * @param ingressProtocol 入口协议，决定响应用哪种格式编码回去
 * @param requestedModel  客户端原始模型名（用于回显）
 * @param virtualModel    归一化后的路由键
 * @param passthrough     无法归一化但需要原样回传的字段（如同一协议的扩展参数）
 * @param droppedFields   转换过程中丢弃的字段名，用于日志与告警
 */
public record CanonicalRequest(
        String requestId,
        long tenantId,
        long apiKeyId,
        Protocol ingressProtocol,
        String requestedModel,
        String virtualModel,
        String system,
        List<Message> messages,
        List<ToolDef> tools,
        ToolDef.ToolChoice toolChoice,
        GenerationParams params,
        ReasoningConfig reasoning,
        boolean stream,
        Map<String, Object> passthrough,
        List<String> droppedFields
) {

    /** 入口协议枚举（与 domain.Protocol 区分：这里描述"报文格式"，domain 描述"渠道能力"）。 */
    public enum Protocol {
        OPENAI_CHAT,
        OPENAI_RESPONSES,
        ANTHROPIC,
        GEMINI
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 构造器。字段多，用 builder 避免位置参数出错。 */
    public static final class Builder {
        private String requestId;
        private long tenantId;
        private long apiKeyId;
        private Protocol ingressProtocol;
        private String requestedModel;
        private String virtualModel;
        private String system;
        private List<Message> messages = List.of();
        private List<ToolDef> tools = List.of();
        private ToolDef.ToolChoice toolChoice;
        private GenerationParams params = GenerationParams.defaults();
        private ReasoningConfig reasoning = ReasoningConfig.disabled();
        private boolean stream;
        private Map<String, Object> passthrough = Map.of();
        private List<String> droppedFields = List.of();

        public Builder requestId(String v) { this.requestId = v; return this; }
        public Builder tenantId(long v) { this.tenantId = v; return this; }
        public Builder apiKeyId(long v) { this.apiKeyId = v; return this; }
        public Builder ingressProtocol(Protocol v) { this.ingressProtocol = v; return this; }
        public Builder requestedModel(String v) { this.requestedModel = v; return this; }
        public Builder virtualModel(String v) { this.virtualModel = v; return this; }
        public Builder system(String v) { this.system = v; return this; }
        public Builder messages(List<Message> v) { this.messages = v; return this; }
        public Builder tools(List<ToolDef> v) { this.tools = v; return this; }
        public Builder toolChoice(ToolDef.ToolChoice v) { this.toolChoice = v; return this; }
        public Builder params(GenerationParams v) { this.params = v; return this; }
        public Builder reasoning(ReasoningConfig v) { this.reasoning = v; return this; }
        public Builder stream(boolean v) { this.stream = v; return this; }
        public Builder passthrough(Map<String, Object> v) { this.passthrough = v; return this; }
        public Builder droppedFields(List<String> v) { this.droppedFields = v; return this; }

        public CanonicalRequest build() {
            return new CanonicalRequest(requestId, tenantId, apiKeyId, ingressProtocol, requestedModel,
                    virtualModel, system, messages, tools, toolChoice, params, reasoning, stream,
                    passthrough, droppedFields);
        }
    }
}
