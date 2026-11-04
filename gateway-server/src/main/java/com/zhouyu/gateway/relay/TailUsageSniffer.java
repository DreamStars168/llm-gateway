package com.zhouyu.gateway.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式用量嗅探器。
 *
 * <p><b>每请求一个实例</b>（非单例）：内部持有 request 级状态，共享会导致串数据。
 *
 * <p>透传模式下不解析每一帧，只在尾部保留窗口，流结束后提取 usage：
 * <ul>
 *   <li>Anthropic：{@code message_start} 给 input_tokens，{@code message_delta} 给 output_tokens</li>
 *   <li>OpenAI：最后一个含 {@code "usage":{...}} 的帧</li>
 * </ul>
 */
public final class TailUsageSniffer {

    private static final int TAIL_LIMIT = 16 * 1024;

    private static final Pattern INPUT_TOKENS = Pattern.compile("\"input_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern OUTPUT_TOKENS = Pattern.compile("\"output_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern CACHE_READ = Pattern.compile("\"cache_read_input_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern PROMPT_TOKENS = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern COMPLETION_TOKENS = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");

    private final ObjectMapper mapper;
    private final StringBuilder tail = new StringBuilder();
    private int inputTokens;
    private int outputTokens;
    private int cachedTokens;
    private String usageSource = "ESTIMATED";
    private long totalBytes;

    public TailUsageSniffer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 每帧调用。使用 {@code toString} 读取而不移动读指针，
     * 保证下游仍能正常消费同一份 DataBuffer。
     */
    public void accept(DataBuffer buf) {
        totalBytes += buf.readableByteCount();
        String s = buf.toString(StandardCharsets.UTF_8);
        synchronized (tail) {
            tail.append(s);
            if (tail.length() > TAIL_LIMIT) {
                tail.delete(0, tail.length() - TAIL_LIMIT);
            }
        }
        parseChunk(s);
    }

    private void parseChunk(String chunk) {
        if (chunk.contains("\"usage\"")) {
            usageSource = "UPSTREAM";
        }
        Matcher mi = INPUT_TOKENS.matcher(chunk);
        if (mi.find()) {
            inputTokens = Math.max(inputTokens, Integer.parseInt(mi.group(1)));
        }
        Matcher mo = OUTPUT_TOKENS.matcher(chunk);
        if (mo.find()) {
            outputTokens = Math.max(outputTokens, Integer.parseInt(mo.group(1)));
        }
        Matcher mc = CACHE_READ.matcher(chunk);
        if (mc.find()) {
            cachedTokens = Math.max(cachedTokens, Integer.parseInt(mc.group(1)));
        }
        Matcher mp = PROMPT_TOKENS.matcher(chunk);
        if (mp.find()) {
            inputTokens = Math.max(inputTokens, Integer.parseInt(mp.group(1)));
        }
        Matcher mq = COMPLETION_TOKENS.matcher(chunk);
        if (mq.find()) {
            outputTokens = Math.max(outputTokens, Integer.parseInt(mq.group(1)));
        }
    }

    /**
     * 非流式响应：整包解析。
     *
     * <p>兼容三种格式（因为转换后的响应可能是任一协议）：
     * <ul>
     *   <li>OpenAI：{@code usage.prompt_tokens / completion_tokens}</li>
     *   <li>Anthropic：{@code usage.input_tokens / output_tokens}（转换后的结果走这里）</li>
     *   <li>Gemini：{@code usageMetadata.promptTokenCount / candidatesTokenCount}</li>
     * </ul>
     */
    public void parseWhole(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);

            // Anthropic 格式（转换后的响应）
            JsonNode usage = root.get("usage");
            if (usage != null) {
                usageSource = "UPSTREAM";
                if (usage.has("input_tokens")) inputTokens = usage.get("input_tokens").asInt();
                if (usage.has("output_tokens")) outputTokens = usage.get("output_tokens").asInt();
                if (usage.has("prompt_tokens")) inputTokens = usage.get("prompt_tokens").asInt();
                if (usage.has("completion_tokens")) outputTokens = usage.get("completion_tokens").asInt();
                if (usage.has("cache_read_input_tokens")) cachedTokens = usage.get("cache_read_input_tokens").asInt();
                return;
            }

            // Gemini 原始格式（未经过非流式转换时）
            JsonNode usageMeta = root.get("usageMetadata");
            if (usageMeta != null) {
                usageSource = "UPSTREAM";
                inputTokens = usageMeta.path("promptTokenCount").asInt(0);
                outputTokens = usageMeta.path("candidatesTokenCount").asInt(0);
                cachedTokens = usageMeta.path("cachedContentTokenCount").asInt(0);
            }
        } catch (Exception ignored) {
            // 保持 ESTIMATED
        }
    }

    public int inputTokens() { return inputTokens; }
    public int outputTokens() { return outputTokens; }
    public int cachedTokens() { return cachedTokens; }
    public String usageSource() { return usageSource; }
    public long totalBytes() { return totalBytes; }

    /**
     * 翻译链路专用：直接从规范事件流累计用量。
     * 透传链路的字节嗅探拿不到已解码的 usage，两条路径必须分开记。
     */
    public void acceptCanonical(com.zhouyu.gateway.protocol.canonical.CanonicalChunk chunk) {
        if (chunk instanceof com.zhouyu.gateway.protocol.canonical.CanonicalChunk.UsageChunk u) {
            if (u.inputTokens() > 0 || u.outputTokens() > 0) {
                this.inputTokens = Math.max(this.inputTokens, u.inputTokens());
                this.outputTokens = Math.max(this.outputTokens, u.outputTokens());
                this.cachedTokens = Math.max(this.cachedTokens, u.cachedTokens());
                this.usageSource = "UPSTREAM";
            }
        } else if (chunk instanceof com.zhouyu.gateway.protocol.canonical.CanonicalChunk.TextDelta t) {
            // 兜底估算：按字符数粗估 token（无 usage 时）。
            // 中文约 1 字 ≈ 1 token，英文约 4 字符 ≈ 1 token，这里用保守的 3 字符/token。
            this.estimatedOutputChars += t.text().length();
            if (this.estimatedOutputChars > 0 && "ESTIMATED".equals(this.usageSource)) {
                this.outputTokens = Math.max(1, this.estimatedOutputChars / 3);
            }
        }
    }

    private int estimatedOutputChars;
}
