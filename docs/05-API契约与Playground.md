# 05 API 契约与 Playground

> 目的：前后端可并行开发，无需口头对齐。

---

## 1. 面向前端的对外 API（OpenAI 兼容）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/chat/completions` | OpenAI Chat，支持 `stream` |
| POST | `/v1/responses` | OpenAI Responses |
| POST | `/v1/messages` | Anthropic Messages |
| POST | `/v1beta/models/{model}:generateContent` | Gemini 非流式 |
| POST | `/v1beta/models/{model}:streamGenerateContent` | Gemini 流式（`?alt=sse`） |
| POST | `/v1/embeddings` | P2 |
| GET | `/v1/models` | 返回当前 Key 可见的虚拟模型（OpenAI 格式） |
| GET | `/health` | 存活探针（不查依赖） |
| GET | `/ready` | 就绪探针（Redis/DB 可达） |

### 1.1 `GET /v1/models` 响应

```json
{
  "object": "list",
  "data": [
    {
      "id": "claude-sonnet-4",
      "object": "model",
      "created": 1735689600,
      "owned_by": "gateway",
      "context_window": 200000,
      "capabilities": ["text", "vision", "tools", "thinking"]
    }
  ]
}
```

---

## 2. 管理端 API

统一前缀 `/admin/api/v1`，JWT 鉴权，统一响应包装：

```json
{ "code": 0, "message": "ok", "data": {...}, "request_id": "req_..." }
```

### 2.1 渠道

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/channels?page=1&size=20&group=&status=` | 分页 |
| POST | `/channels` | 新建（凭证加密） |
| PUT | `/channels/{id}` | 更新（支持部分字段） |
| DELETE | `/channels/{id}` | 软删（有流量时拒绝） |
| POST | `/channels/{id}/test` | 连通性测试（发最小请求） |
| POST | `/channels/{id}/keys` | 追加凭证 |
| POST | `/channels/{id}/sync-models` | 拉取上游模型列表 |
| GET | `/channels/{id}/health` | 熔断状态、余额、近 1h 成功率 |

请求体示例：

```json
{
  "name": "openai-main",
  "provider": "openai",
  "protocol": "OPENAI_CHAT",
  "baseUrl": "https://api.openai.com",
  "groupId": 1,
  "priority": 10,
  "weight": 100,
  "maxInflight": 300,
  "timeoutMs": 60000,
  "passthrough": true,
  "apiKey": "sk-...",
  "extra": { "proxy": null, "headers": { "OpenAI-Beta": "assistants=v2" } }
}
```

### 2.2 虚拟模型与路由

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/virtual-models` | 列表（含路由数与健康度） |
| POST | `/virtual-models` | 新建 |
| PUT | `/virtual-models/{id}` | 更新 |
| PUT | `/virtual-models/{id}/routes` | **全量覆盖**路由（前端拖拽排序后提交） |
| POST | `/virtual-models/{id}/aliases` | 添加别名 |
| POST | `/virtual-models/{id}/playground` | 试跑（复用数据面管线，强制非流式） |

路由覆盖请求体（拖拽排序 = priority 由顺序决定）：

```json
{
  "routes": [
    { "channelId": 3, "upstreamModel": "claude-sonnet-4-20250514", "weight": 70, "enabled": true },
    { "channelId": 7, "upstreamModel": "claude-sonnet-4-0",        "weight": 30, "enabled": true }
  ],
  "policy": { "strategy": "STICKY", "stickyTtlSeconds": 1800, "retryMax": 2 }
}
```

### 2.3 API Key

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/keys` | 列表（只显示前缀，永不返回明文） |
| POST | `/keys` | 创建，**响应中仅此一次返回明文** |
| PUT | `/keys/{id}` | 改名/限流/禁用 |
| DELETE | `/keys/{id}` | 删除 |
| POST | `/keys/{id}/rotate` | 轮换：生成新 secret，旧 key 保留 24h 双活 |

### 2.4 日志与统计

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/logs?from=&to=&model=&channel=&status=&requestId=` | 分页，强制时间范围 ≤ 7 天 |
| GET | `/logs/{requestId}` | 详情（含 route_trace、attempts） |
| GET | `/stats/overview?range=24h` | 请求数/Token/成本/成功率/P99 |
| GET | `/stats/timeseries?metric=tokens&interval=1h` | 折线图数据 |
| GET | `/stats/breakdown?by=model` | 按模型/渠道/Key 分组 |
| GET | `/stats/export` | 导出 CSV（异步任务，返回 taskId） |

### 2.5 定价

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST/PUT | `/prices` | 分级定价 CRUD |
| POST | `/prices/simulate` | 输入 token 数，试算成本（前端实时展示） |

---

## 3. Playground 设计

### 3.1 功能清单

| 功能 | 说明 |
|---|---|
| 多模型并排 | 2~4 列，每列独立选择虚拟模型与渠道 |
| 参数实时调节 | `temperature`、`top_p`、`top_k`、`max_tokens`、`stop`、`reasoning effort`，滑块即时生效（下一次发送） |
| 流式渲染 | 每列独立 SSE，逐字追加，互不阻塞 |
| 同屏对比 | 显示每列 TTFT、总耗时、Token、成本 |
| Prompt 版本 | 每次发送自动存 `prompt_version`（可归档、可命名、可回滚） |
| 会话保存 | 保存整段对话为 case，可重放 |
| 导出 | 导出 Markdown / JSON |

### 3.2 前端实现要点

```ts
// useStreamCompare.ts
export function useStreamCompare() {
  const columns = ref<Column[]>([createColumn(), createColumn()]);
  const controllers = new Map<string, AbortController>();

  async function run(col: Column, body: ChatRequest) {
    const ac = new AbortController();
    controllers.set(col.id, ac);
    const res = await fetch('/v1/chat/completions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${col.key}` },
      body: JSON.stringify({ ...body, model: col.model, stream: true }),
      signal: ac.signal,
    });
    const reader = res.body!.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    const t0 = performance.now();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      const frames = buf.split('\n\n');
      buf = frames.pop()!;
      for (const f of frames) {
        const line = f.split('\n').find(l => l.startsWith('data: '));
        if (!line) continue;
        const data = line.slice(6);
        if (data === '[DONE]') continue;
        const chunk = JSON.parse(data);
        if (col.ttft == null) col.ttft = performance.now() - t0;
        col.text += chunk.choices?.[0]?.delta?.content ?? '';
      }
    }
  }
  return { columns, run };
}
```

**关键约束**：
- 必须用 `fetch` + `ReadableStream`，不能用 `EventSource`（它只支持 GET，无法带 body）。
- 每列独立 `AbortController`，支持"停止"单列。
- 用 `requestAnimationFrame` 批量刷新 DOM，避免每字符触发渲染（1000 token 会卡顿）。

### 3.3 Prompt 版本表

```sql
CREATE TABLE prompt_version (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id   BIGINT NOT NULL,
  name        VARCHAR(128) NULL,
  model       VARCHAR(64)  NOT NULL,
  params      JSON         NOT NULL,
  messages    JSON         NOT NULL,
  tags        JSON         NULL,
  parent_id   BIGINT       NULL,      -- 版本链
  created_by  BIGINT       NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_tenant_time (tenant_id, created_at)
);
```

- 每次发送都插入（可关闭），`parent_id` 形成版本链，前端可 diff 两次版本。
- 存储上限：单条 `messages` > 256KB 时拒绝并提示。

---

## 4. 错误与幂等约定

| 场景 | 约定 |
|---|---|
| 管理端 POST 重复提交 | 客户端带 `Idempotency-Key`，服务端 24h 内去重 |
| 渠道删除时仍有流量 | 返回 409 `channel_in_use`，提示先迁移路由 |
| 路由覆盖后某模型无候选 | 返回 200 但在 `warnings` 中提示 |
| 并发修改冲突 | `updated_at` 乐观锁，冲突返回 409 |

---

## 5. 前端页面结构

```
gateway-console/
├── src/
│   ├── views/
│   │   ├── Dashboard/          # 概览：QPS、Token、成本、成功率、Top 模型
│   │   ├── Channels/           # 渠道列表 + 抽屉编辑 + 测试连通
│   │   ├── Models/             # 虚拟模型 + 拖拽路由 + 别名
│   │   ├── Keys/               # Key 管理（明文仅创建时展示）
│   │   ├── Logs/               # 日志检索 + 详情抽屉（路由追踪时间线）
│   │   ├── Pricing/            # 分级定价 + 试算器
│   │   ├── Playground/         # 多列对比
│   │   └── Settings/           # 租户、告警、审计
│   ├── api/                    # 自动生成（openapi-typescript）
│   ├── stores/                 # Pinia
│   └── components/
```

- 图表：ECharts（`vue-echarts`）。
- 表格：Element Plus `el-table` + 虚拟滚动（日志量大）。
- 路由追踪可视化：`el-timeline` 展示"候选 → 过滤原因 → 选中 → 重试"。

---

## 6. 契约测试

- 后端用 `springdoc-openapi` 生成 `/v3/api-docs`。
- CI 中 `openapi-typescript` 生成 TS 类型，前端 `tsc --noEmit` 校验，接口变更会导致编译失败。
- 关键接口补 `WebTestClient` 集成测试（鉴权、分页、错误码）。
