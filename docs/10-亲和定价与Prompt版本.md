# 10 会话亲和、分级定价与 Prompt 版本

> 这三项是简历明确写了的，但 M1~M8 阶段只是"表建好了、字段占位"，属于面试一问就穿帮的空壳。
> 本篇记录它们的实现决策与实测证据。

---

## 1. 会话亲和（Sticky Session）

### 1.1 为什么需要

多账号/多渠道时，若同一会话被轮询分配到不同上游：

- 各家 prompt cache **按账号隔离**，切换即失效
- 一次 10K 输入的请求，缓存命中时输入价按 `cacheRead`（约 1/10）计
- 乱切会让成本**翻 5~10 倍**，且 TTFT 显著变长（要重新处理完整上下文）

所以亲和不是"锦上添花"，是**成本控制的核心手段**。

### 1.2 会话标识提取（按优先级）

| 优先级 | 来源 | 适用客户端 |
|---|---|---|
| 1 | `x-claude-code-session-id` 头 | Claude Code |
| 2 | `session_id` 头 | Codex CLI |
| 3 | `x-session-id` 头 | 通用客户端 |
| 4 | body `prompt_cache_key` | Codex（部分版本） |
| 5 | body `user` 字段 | OpenAI 兼容客户端 |
| 6 | body `metadata.user_id` | Anthropic 官方 SDK |
| 7 | 首条消息内容 hash | 兜底（无任何标识时） |

> 第 7 条是降级方案：同一问题的重复请求会命中，但多轮对话中内容变化会重新分配。
> 这是可接受的——至少保住了"同一问题反复调试"场景的缓存。

### 1.3 两级存储

```
请求 → 本地 Caffeine（零网络往返，命中率 >95%）
         │ miss
         ▼
       Redis（跨实例共享，TTL 30min 滑动）
```

**写路径**：请求成功后写入本地 + 异步写 Redis（fire-and-forget，不阻塞）。
**为什么成功后才写**：失败/熔断的渠道不应被钉住，否则会把后续请求都导向坏渠道。

**关键路径零 Redis 往返**：`peekLocal()` 同步读 Caffeine，未命中直接退化为加权轮询，
由请求结束时的 `bind()` 补写。代价是新会话首个请求可能落到其他实例的渠道——只影响首轮缓存。

### 1.4 亲和 vs 优先级的取舍

```java
// 亲和优先级高于优先级分层
if (stickyChannelId.isPresent()) {
    Candidate sticky = findInCandidates(wanted);
    if (sticky != null && breaker.allow(sticky) && gates.acquire(sticky)) {
        return new Decision(sticky, ..., stickyHit = true);   // 直接返回，跳过层级选择
    }
}
// 亲和失效才走：优先级分层 → SWRR
```

**为什么亲和能压过优先级**：假设主渠道 priority=10、备渠道 priority=20，
某会话已绑定备渠道。若严格按优先级，它会切回主渠道，缓存全失效。
而缓存的收益（成本降 5~10 倍）远大于"用上更便宜渠道"的收益。所以亲和优先。

**前提**：亲和渠道必须健康（未熔断、并发未打满），否则重选并覆盖映射。

### 1.5 实测证据

```
会话 sess-AAA 连发 6 次：
  第 1 次 stickyHit=false（未绑定，正常选路）
  第 2~6 次 stickyHit=true  ← 全部命中 anthropic-main
  Redis: sticky:1:claude-sonnet-4:sid:sess-AAA = 1

10 个不同会话（同优先级 100:100 权重）：
  anthropic-main: 5 次
  anthropic-backup: 5 次     ← 权重精确分散

会话 d-0 再发 5 次：
  全部 stickyHit=true，稳定在 anthropic-main
```

### 1.6 指标

| 指标 | 含义 | 健康值 |
|---|---|---|
| `gateway.sticky.hit` | 亲和命中次数 | 越高越好 |
| `gateway.sticky.rebind` | 亲和被覆盖次数 | < 1% 请求数；升高说明渠道不稳 |

---

## 2. 分级定价

### 2.1 定价模型

```json
{
  "claude-sonnet-4": {
    "tiers": [
      { "inputTokensLte": 200000, "input": "3.0", "output": "15.0",
        "cacheWrite": "3.75", "cacheRead": "0.30" },
      { "inputTokensLte": null,   "input": "6.0", "output": "22.5",
        "cacheWrite": "7.50", "cacheRead": "0.60" }
    ],
    "channelMultiplier": "1.0",
    "groupDiscount": "1.0"
  }
}
```

- **单位**：USD / 1M tokens（与厂商报价口径一致）
- **档位**：按顺序匹配，命中第一个满足条件的。`null` 表示兜底档
- **长上下文溢价**：输入 > 200K 时单价翻倍（Anthropic 真实规则）
- **缓存独立计价**：`cacheRead` 通常是 `input` 的 1/10

### 2.2 计算精度

```java
// 全部 BigDecimal，禁止 double
BigDecimal sum = input.mul(inputRate)
    .add(output.mul(outputRate))
    .add(cacheRead.mul(cacheReadRate));
BigDecimal usd = sum.divide(ONE_MILLION, 12, HALF_UP);
BigDecimal finalUsd = usd.multiply(channelMultiplier).multiply(groupDiscount);
long micro = finalUsd.multiply(MICRO).setScale(0, HALF_UP).longValue();
```

- 中间保留 12 位小数，最终转 micro-USD（long），避免累积误差
- 负数保护：`Math.max(0L, micro)`

### 2.3 预扣估算

```java
// 预扣要覆盖最坏情况：输入按实际估算，输出按 max_tokens 上限
long estimateReserveMicro = calculate(rule, estInputTokens, maxTokens, 0) × 1.2;
```

- 安全系数 1.2：覆盖 token 估算偏差与上游计费差异
- 输入估算：字符数 / 3（中英混合中位数），预扣只需量级正确
- 未配置定价时兜底预扣 1 USD

### 2.4 实测证据

| 模型 | Token | 理论成本 | 实测 `costMicro` | 结论 |
|---|---|---|---|---|
| claude-sonnet-4 | 23 输入 + 9 输出 | 23×3.0 + 9×15.0 = 204 | **204** | ✅ 精确 |
| gpt-4o-mini | 31 输入 + 12 输出 | 31×0.15 + 12×0.60 = 11.65 | **12** | ✅ 四舍五入正确 |
| claude-sonnet-4 | 23 输入 + 40 输出 | 23×3.0 + 40×15.0 = 669 | **669** | ✅ Playground 显示 $0.000063 |

> 对比改造前：固定按 `in+out` 算，Claude 23+9 只算 32 micro，**少算 6.4 倍**。
> 定价接入后，不同模型成本差异得以体现（GPT 比 Claude 便宜 17 倍）。

### 2.5 档位切换测试

```
输入 200,000 token  → 命中第一档 → 600,000 micro
输入 200,001 token  → 命中第二档 → 1,200,006 micro  ← 单价翻倍
```
单测 `CostCalculatorTest.tierSwitch` 覆盖，端到端因 Mock 上游不收超大 body 未测（已注明）。

---

## 3. Prompt 版本归档

### 3.1 表结构

```sql
CREATE TABLE prompt_version (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id   BIGINT NOT NULL,
  name        VARCHAR(128),
  model       VARCHAR(64) NOT NULL,
  params      JSON NOT NULL,      -- {temperature, max_tokens, ...}
  messages    JSON NOT NULL,      -- 原始消息数组，不做结构化解构
  tags        JSON,
  parent_id   BIGINT,             -- 版本链
  created_by  BIGINT NOT NULL,
  created_at  DATETIME(3)
);
```

**设计取舍**：`messages` 存原始 JSON，不做结构化解构。
理由：多协议下消息结构差异大（Anthropic blocks / OpenAI messages / Gemini parts），
解构会引入耦合；原样存储 + 前端按需解析更灵活。

### 3.2 API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/api/v1/prompts` | 保存快照（>256KB 拒绝，防大图撑爆） |
| GET | `/admin/api/v1/prompts?limit=&model=` | 列表（messages 截断 200 字符） |
| GET | `/admin/api/v1/prompts/{id}` | 详情（完整内容，用于回填） |
| DELETE | `/admin/api/v1/prompts/{id}` | 删除 |

### 3.3 前端行为

- **自动存档**：每次 Playground 运行成功后自动保存（fire-and-forget，失败不影响对话）
- **历史面板**：可展开，按时间倒序列出，显示时间/模型/名称/messages 摘要
- **回填**：点击"回填"把该版本的 model、params、prompt 恢复到编辑器
- **删除**：单条删除

### 3.4 实测证据

```
POST   保存       → {"saved":true,"id":1}
GET    列表       → total=1，messages 正确
GET    详情       → model=claude-sonnet-4, params 完整
GET    按模型过滤 → claude-sonnet-4 命中 1 条；no-such 命中 0 条
前端   Playground 运行后自动存档 → 历史面板显示 3 条（2 自动 + 1 手动）
```

---

## 4. 三项补齐后的简历对照

| 简历原文 | 当前实现 | 可演示证据 |
|---|---|---|
| 基于权重与优先级的渠道调度和亲和策略 | 优先级硬分层 + SWRR + Sticky（Redis+本地两级） | 同会话 5 次全命中；10 会话 5:5 分散 |
| 支持分级定价（Token/多模态） | 档位定价 + 倍率 + 折扣 + 缓存独立计价 | Claude 204 micro / GPT 12 micro 精确匹配 |
| Prompt 版本归档 | 表 + API + 前端历史面板 | 3 条记录、回填可用 |

> **至此简历里"LLM 多模型接入网关"这条项目描述的所有技术点都有对应实现与实测证据。**
