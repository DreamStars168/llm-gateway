# 11 Gemini 协议适配

> 目标：让 Anthropic 客户端（Claude Code）能透明使用 Gemini 上游。
> 这是简历"预置 OpenAI / Anthropic / Gemini 及兼容服务模板"的最后一块。

---

## 1. 为什么 Gemini 最麻烦

三家协议里，Gemini 与另外两家的差异最大，不是简单字段改名：

| 维度 | OpenAI Chat | Anthropic | Gemini |
|---|---|---|---|
| 角色 | `system/user/assistant/tool` | `user/assistant` + 顶层 system | **只有 `user` / `model`** |
| 系统提示 | messages 首条 | 顶层 `system` | 顶层 `systemInstruction` |
| 工具结果 | 独立 `role=tool` 消息 | user 消息内的 `tool_result` block | user 消息的 `functionResponse` part |
| 工具调用 ID | 有 `id` | 有 `id`（toolu_xxx） | **无 id，靠 name 关联** |
| 参数位置 | 顶层 | 顶层 | **`generationConfig` 内** |
| Schema 类型 | 小写 `object/string` | 小写 | **大写 `OBJECT/STRING`** |
| 流结束标志 | `[DONE]` | `message_stop` | **无标记，靠连接关闭** |
| usage 位置 | 末尾单独帧 | `message_start` + `message_delta` | 每帧带 `usageMetadata`（累计） |
| 思考 | `reasoning_content` | `thinking` block | `thought: true` + `thoughtSignature` |

---

## 2. 实现结构

```
Anthropic 请求
   │ AnthropicParser（已有）
   ▼
CanonicalRequest（IR）
   │
   ├─ GeminiRequestBuilder  ──▶ generateContent / streamGenerateContent
   │                                 │
   │                                 ▼
   │                          GeminiStreamDecoder ──▶ CanonicalChunk
   │                                                      │
   └──────────────────────────────────────────────────────┘
                                                      ▼
                                          AnthropicSseEncoder（已有）
                                                      ▼
                                              Anthropic SSE 响应
```

**关键收益**：Gemini 适配只新增了 3 个类（Builder / Decoder / NonStream 转换），
因为「解码 → 规范事件 → 编码」的骨架早已存在。这是 IR 设计的直接回报。

---

## 3. 关键转换细节

### 3.1 角色映射

```java
// Message.Role.geminiWire()
ASSISTANT → "model"
其他（SYSTEM/USER/TOOL） → "user"
```

system 不走 contents，而是顶层 `systemInstruction`：

```json
{
  "systemInstruction": { "parts": [{ "text": "You are terse." }] },
  "contents": [
    { "role": "user",  "parts": [{ "text": "hi" }] },
    { "role": "model", "parts": [{ "text": "hello" }] }
  ]
}
```

### 3.2 Schema 类型必须大写

这是最容易踩的坑。JSON Schema 用 `object`，Gemini 要求 `OBJECT`：

```java
private JsonNode sanitizeSchema(JsonNode schema) {
    copy.put("type", type.toUpperCase());   // object → OBJECT
    // 递归处理 properties / items
}
```

**验证方式**：Mock 上游会主动校验，若传小写直接返回 400
（`Invalid schema type: must be uppercase`）——用严格 Mock 逼出真实约束。

### 3.3 工具调用无 ID

Gemini 的 `functionCall` 只有 `name` 和 `args`，没有 id。而 Anthropic / OpenAI 都需要 id。

处理方式：流入方向（Gemini → IR）合成稳定 id：
```java
String id = "call_" + name + "_" + toolIndex;   // call_get_weather_0
```

流出方向（IR → Gemini）丢弃 id，只传 `name` + `args`。
**已知限制**：若同一轮有同名工具多次调用，反查会歧义（见第 6 节）。

### 3.4 流式结束

Gemini 没有 `[DONE]`，连接关闭即结束。若网关不补收尾事件，客户端会一直等：

```java
.concatWith(Flux.defer(() -> {
    if (finished[0]) return Flux.empty();
    String closing = encoder.encode(new CanonicalChunk.Finish("end_turn"));
    return closing.isEmpty() ? Flux.empty() : Flux.just(closing);
}))
```

### 3.5 usage 是累计值

Gemini 每个 chunk 都带 `usageMetadata`，且是**累计**的（不是增量）。
`TailUsageSniffer.acceptCanonical()` 用 `Math.max` 而非 `+=`，避免重复累加。

---

## 4. 实测证据

### 4.1 流式（Anthropic 入口 → Gemini 出口）

```
POST /v1/messages  model=gemini-2.5-pro  stream=true
→ HTTP 200

event: message_start
data: {"type":"message_start","message":{"id":"gem_resp_4bd9ff29b484",...,"model":"gemini-2.5-pro",...}}

event: content_block_start
event: content_block_delta  text="hello "
event: content_block_delta  text="gem2 "
event: content_block_delta  text="gem3 "
event: content_block_delta  text="gem4 "
event: content_block_delta  text="gem5 "
event: content_block_stop

event: message_delta
data: {...,"usage":{"input_tokens":27,"output_tokens":10}}
event: message_stop
```

✅ 事件顺序完全符合 Anthropic 规范
✅ usage 与 Mock 返回的 `promptTokenCount=27 / candidatesTokenCount=10` 一致

### 4.2 非流式

```
POST /v1/messages  stream=false
→ HTTP 200

{"id":"msg_gw_...","type":"message","role":"assistant",
 "model":"gemini-2.5-pro",
 "content":[{"type":"text","text":"Hello from Gemini mock."}],
 "stop_reason":"end_turn",
 "usage":{"input_tokens":27,"output_tokens":10}}
```

✅ 标准 Anthropic 响应格式

### 4.3 计费

```
Gemini 27 输入 + 10 输出
理论 = 27×1.25/1M + 10×10.0/1M = 0.00013375 USD ≈ 134 micro
实测 costMicro=134  src=UPSTREAM   ✅ 精确
```

### 4.4 单测（11 个）

| 测试 | 覆盖点 |
|---|---|
| `systemAndRoles` | systemInstruction + user/model 角色 |
| `paramsGoIntoGenerationConfig` | 参数位置、顶层不得出现 max_tokens |
| `schemaTypesUppercased` | object→OBJECT 递归转换 |
| `imageToInlineData` | base64 图片 → inlineData |
| `urlShape` | stream 路径带 `?alt=sse` |
| `streamDecode` | Start 只发一次、usage、finish |
| `streamFunctionCall` | functionCall → ToolCallStart/Delta/End |
| `streamThought` | thought → ThinkingDelta |
| `finishReasonMapping` | STOP/MAX_TOKENS/SAFETY 映射 |
| `errorDecode` | 错误响应 → ErrorChunk |
| `nonStreamConvert` | 非流式 → Anthropic 格式 + usage |

---

## 5. 过程中修掉的真实 bug

### bug 1：非流式计费为 0

**现象**：Gemini 非流式请求 `in=0 out=0 costMicro=0 src=ESTIMATED`。

**根因**：`TailUsageSniffer.parseWhole()` 解析的是**转换后**的响应（Anthropic 格式），
但它只认 OpenAI 的 `prompt_tokens`，不认 Anthropic 的 `input_tokens`。

**修复**：支持三种格式（OpenAI / Anthropic / Gemini 原始）。

**教训**：非流式路径的 usage 解析必须考虑"转换后"的格式，不能只按上游格式写。

### bug 2：Mock 不区分流式/非流式

**现象**：非流式请求返回了 SSE 流。

**根因**：Mock 用同一个 `@PostMapping("/v1beta/models/**")` 处理两种路径，
且原先靠"请求里有没有 generationConfig"判断（不可靠）。

**修复**：从 URI 路径判断 `:streamGenerateContent` vs `:generateContent`。

---

## 6. 已知限制（诚实记录）

| 限制 | 影响 | 后续方案 |
|---|---|---|
| 同轮同名工具多次调用 | Gemini 无 id，反查歧义 | 需在 IR 里维护 name→id 映射表 |
| 图片 URL | Gemini 只支持 `fileData`（需 GCS/公开 URL） | 网关下载后转 base64 inlineData |
| `thoughtSignature` 多轮回传 | 当前能读能写，但未做跨轮持久化 | 需在会话状态里保存签名 |
| Schema 高级特性 | `$ref`、`oneOf`、`additionalProperties` 未处理 | Gemini 本身支持有限，需降级或拒绝 |
| Gemini 入口 | 只做了出口（Anthropic 客户端用 Gemini 上游） | 若要让 Gemini SDK 直连网关，需另写 Ingress |

---

## 7. 对简历的意义

简历原文："预置 OpenAI / Anthropic / Gemini 及兼容服务模板"

| 方向 | 状态 |
|---|---|
| Anthropic 入口 → Anthropic 出口 | ✅ 快车道透传 |
| Anthropic 入口 → OpenAI 出口 | ✅ 慢车道翻译 |
| Anthropic 入口 → Gemini 出口 | ✅ **本次完成** |
| 三协议 IR 互通 | ✅ IR + 解码/编码骨架已验证可扩展 |

**至此，简历里"通用协议适配层统一 Chat / Messages / generateContent 格式与 SSE 流式事件"这句话的三种协议都有了实际实现。**
