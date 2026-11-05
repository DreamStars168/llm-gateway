# LLM Gateway

**一个生产取向的多模型 AI 接入网关**：把 OpenAI、Anthropic、Gemini 三类互不兼容的协议统一到一个入口，
用优先级分层、会话亲和、熔断降权决定请求走向，用两阶段配额与阶梯定价把每一分钱算清楚，
并给出可回放的路由决策与可验证的性能数据。

> 后端 Java 17 · Spring Boot 3 · WebFlux · Reactor Netty · Redis · MySQL · RabbitMQ
> 前端 Vue 3 · TypeScript · Element Plus · Vite

<p align="left">
  <img alt="Java" src="https://img.shields.io/badge/Java-17-orange">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen">
  <img alt="Vue" src="https://img.shields.io/badge/Vue-3%20+%20TS-42b883">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-blue">
  <a href="https://github.com/Sakurasign/llm-gateway/actions/workflows/ci.yml">
    <img alt="CI" src="https://github.com/Sakurasign/llm-gateway/actions/workflows/ci.yml/badge.svg">
  </a>
</p>

```bash
git clone https://github.com/Sakurasign/llm-gateway.git
cd llm-gateway
cp .env.example .env   # 填入你自己的密码与密钥
docker compose up -d --build
```

---

## 它解决什么问题

接入大模型时，几乎每个团队都会依次撞上三道墙：

**第一道：协议不通。** Claude Code 只会说 Anthropic Messages，Codex 只会说 Responses，
而你要接的国产模型可能只提供 OpenAI Chat 兼容端点。每换一家上游，客户端配置就要改一遍。

**第二道：路由不稳。** 单一渠道会限流、会 5xx、会突然变慢。更麻烦的是——
横向扩多个账号后，同一会话被轮流打到不同账号，**上游的 prompt cache 全部失效**，
成本可能直接翻 5~10 倍。

**第三道：成本失控。** 不同模型价差 20 倍，长上下文触发溢价，缓存命中与否差 10 倍，
图片按张、音频按秒……如果只在月底看账单，你永远不知道钱花在哪。

这个项目就是为这三道墙写的。

## 核心设计

### 双车道转发：能透传就不翻译

```
同协议（Anthropic 入口 → Anthropic 渠道）
  请求 → 只改 model 字段 → 字节级转发 → 客户端
         零解析、零拷贝、零聚合

跨协议（Anthropic 入口 → OpenAI 渠道）
  请求 → Ingress 解析 → CanonicalRequest → Egress 构造 → 上游
  响应 → SSE 解码 → CanonicalChunk → SSE 编码 → 客户端
```

大多数网关选择"全部翻译"，代价是每请求都要序列化/反序列化整个 body，
并且在 SSE 长连接上破坏流式。这里把透传做成默认路径，翻译只在必要时启用。

### 路由是一条四级流水线

```
候选集（group + 协议兼容 + 渠道启用）
   ↓ 会话亲和命中？→ 是则直接复用（保住 prompt cache）
   ↓ 健康过滤（熔断 / 并发闸门 / 降权）
   ↓ 优先级硬分层（只在最高优先级层内分配）
   ↓ 平滑加权轮询（SWRR，避免权重 5:1 时前 5 个全打 A）
选中渠道
```

**亲和为什么能压过优先级**：假设主渠道优先级更高，但某会话已绑定备渠道。
严格按优先级会让它切回主渠道，缓存全失效。而缓存收益远大于"用上更便宜渠道"，
所以亲和排在优先级之前——但前提是该渠道健康。

### 计费是两阶段提交

流式请求的最终 token 数在响应结束时才知道。只在结束时扣会超卖，只按预估值扣会浪费。

```
reserve(est) ──▶ 上游调用 ──▶ settle(actual)
                    │
                    └─失败──▶ release()
```

三个动作都是**单个 Redis Lua 脚本**，一次 RTT、天然原子。
并发 1000 次、余额恰好够 100 次时，成功数严格等于 100。

### 熔断与降权是两件事

| | 熔断 | 自动降权 |
|---|---|---|
| 粒度 | 渠道 × 模型 | 渠道 × 模型 |
| 行为 | 开关（摘除 / 半开） | 节流（权重按健康度缩放） |
| 目的 | 快速止损 | 让"半死不活"的渠道少接流量但保留探测 |

健康分用指数滑动：成功 `h += (1-h)×0.1`，严重失败 `h -= h×0.5`。
429 限流只轻微惩罚（×0.3），因为它不代表渠道坏了。


---

## 特性

### 协议层

- **三协议互通**：OpenAI Chat / Anthropic Messages / Gemini generateContent，覆盖入口与出口
- **双车道转发**：同协议走字节级透传（零拷贝），跨协议才走 IR 翻译
- **SSE 全链路流式**：不聚合响应，逐帧回写；客户端断开即取消上游（停止计费）
- **工具调用双向转换**：含 Anthropic `tool_use` ↔ OpenAI `tool_calls` ↔ Gemini `functionCall`

### 路由层

- **优先级分层 + 平滑加权轮询（SWRR）**：避免"权重 5:1 时前 5 个请求全打 A"的突发
- **会话亲和（Sticky）**：同一会话钉在同一渠道，保住上游 prompt cache（成本可降 5~10 倍）
- **熔断**：渠道 × 模型粒度，滑动窗口 + 半开探测
- **自动降权**：健康度差的渠道权重被压低但不摘除，保留探测流量以便自愈
- **故障转移**：仅在「尚未向客户端写出首字节」时重试，绝不拼接脏流

### 计费层

- **两阶段配额**：`reserve → settle / release`，Redis Lua 单脚本原子操作
- **分级定价**：阶梯价（长上下文溢价）+ 渠道倍率 + 分组折扣 + 缓存独立计价
- **多模态计价**：图片按张、音频按秒，与 token 计价可叠加
- **异步落库**：RabbitMQ 三队列（明细 / 聚合 / 账单），幂等消费

### 可观测

- Prometheus 指标（QPS、TTFT、附加延迟分位、熔断状态、成本）
- 路由决策可回放：每条请求记录候选、过滤原因、选中、重试
- 管理控制台：概览 / 渠道 / 模型路由 / 请求日志 / Playground
- **明暗主题切换**

## 架构

```
                        ┌──────────────────────────────────────┐
 Claude Code ─/v1/messages─┐                                   │
 Codex ───────/v1/responses┤  ┌──────────┐   ┌─────────────┐  │
 Cherry Studio─/v1/chat/…──┼─▶│ 接入层    │──▶│ 协议适配层   │  │
 自研 App ────/v1beta/…────┘  │ Auth/限流 │   │ Ingress→IR  │  │
                             └──────────┘   │ IR→Egress   │  │
                                            └──────┬──────┘  │
                                                   ▼         │
                             ┌──────────────────────────────┐│
                             │ 路由：候选→亲和→优先级→SWRR   ││
                             │       →熔断→降权→并发闸门     ││
                             └──────────────┬───────────────┘│
                                            ▼                │
                             ┌──────────────────────────────┐│
                             │ 转发：快车道透传 / 慢车道翻译  ││
                             └──────────────┬───────────────┘│
                                            │                │
        ┌──────────────┬──────────────┬─────┴──────┬─────────┘
        ▼              ▼              ▼            ▼
    OpenAI        Anthropic        Gemini     自建 vLLM

    旁路：RabbitMQ → 用量落库 / 聚合 / 账单
    支撑：Redis（配额/亲和/熔断）  MySQL（配置/账单）  Caffeine（本地缓存）
```

## 快速开始

### 前置

- JDK 17+
- Maven 3.9+
- Docker（用于 MySQL / Redis / RabbitMQ）

### 1. 配置凭证

```bash
cp .env.example .env
```

编辑 `.env`，**必须**设置：

| 变量 | 说明 |
|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL 密码 |
| `RABBIT_PASS` | RabbitMQ 密码 |
| `ADMIN_USERNAME` | 管理端用户名 |
| `ADMIN_PASSWORD` | 管理端密码（>= 8 位，可用 BCrypt 哈希） |
| `ADMIN_JWT_SECRET` | JWT 密钥（>= 32 字节，`openssl rand -hex 32` 生成） |

> ⚠️ 本项目**不提供默认密码**。缺少上述变量时应用会**启动失败并给出明确提示**，
> 这是刻意的 fail-fast 设计，避免演示密码被误用到生产。

### 2. 启动

```bash
docker compose up -d --build
```

访问 `http://localhost:18081`（管理端）与内置 Mock 上游。

### 3. 本地开发

```bash
# 依赖
docker compose up -d redis mysql rabbitmq mock-primary mock-backup

# 后端（需先 export 上述环境变量）
mvn -DskipTests package
java -jar gateway-server/target/gateway-server-*.jar

# 前端
cd gateway-console
npm install
npm run dev     # http://localhost:5173
```

## 使用示例

```bash
# Anthropic 协议调用（Claude Code 可直接用）
curl -N http://localhost:18081/v1/messages \
  -H "x-api-key: $DEMO_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-sonnet-4","max_tokens":100,"stream":true,
       "messages":[{"role":"user","content":"hello"}]}'
```

跨协议自动翻译：把 `model` 换成 `gpt-4o-mini` 或 `gemini-2.5-pro`，
网关会自动转换协议并返回 Anthropic 格式的响应。

## 实测数据

环境：单机，Mock 上游（首字节 ~200ms，单请求约 750ms）。

| 指标 | 结果 |
|---|---|
| 吞吐（200 并发） | 248.3 req/s，**零错误**（直连基线 261.9，达 94.8%） |
| 网关附加延迟（20 并发） | P50 **2.4ms** / P95 5.9ms / P99 5.1ms |
| 配额并发扣减 | 余额 100 时恰好 100 次成功，**零超卖** |
| 计费精度 | Claude 23+9 token → 204 micro（理论值一致） |
| 多模态 | 1 张图 + 23/9 token → 40204 micro（204 + 40000） |

完整数据与瓶颈分析见 [04-运维与压测](docs/04-运维与压测.md)。

> **诚实说明**：50 并发时 P99 升至 ~19ms（Redis 单连接排队）。
> 本项目实测上限为 200 并发零错误，不对更高并发做承诺。

## 文档

| 文档 | 内容 |
|---|---|
| [01-架构设计](docs/01-架构设计.md) | 分层、关键决策、时序、延迟预算 |
| [02-协议适配](docs/02-协议适配.md) | IR 模型、三协议差异矩阵、SSE 处理 |
| [03-路由与计费](docs/03-路由与计费.md) | 调度算法、亲和、熔断、降权、配额与定价 |
| [04-运维与压测](docs/04-运维与压测.md) | 指标、日志、告警、压测数据与瓶颈分析 |
| [05-设计参考](docs/05-设计参考.md) | 参考项目与差异说明 |
| [RUNBOOK](RUNBOOK.md) | 启动、验证、故障演练 |

## 项目结构

```
gateway-server/    后端（Spring Boot + WebFlux）
  ├── protocol/    协议适配（IR + 三协议）
  ├── routing/     路由、亲和、熔断、降权
  ├── quota/       两阶段配额
  └── usage/       MQ 异步落库
gateway-console/   前端（Vue 3 + TS + Element Plus）
mock-upstream/     Mock 上游（三协议）
db/                schema.sql + Lua 脚本
scripts/           压测工具
docs/              设计文档
```

## 开发

```bash
# 后端测试
mvn test

# 前端类型检查 + 构建
cd gateway-console && npm run build
```

测试覆盖：74 个单元测试（协议转换、路由、计费、配额、降权）。

## 已知限制

- 仅支持 Anthropic / OpenAI Chat / Gemini 三类出口协议；Responses 协议未实现
- 配置来自 `application.yml`，无热更新（改配置需重启）
- 管理端只有只读 API（增删渠道需改配置）
- 未验证多实例部署（亲和与限流依赖 Redis，理论上可水平扩展）

## 安全说明

- 管理端凭证与 JWT 密钥**必须**通过环境变量提供，无默认值
- 上游凭证建议加密存储（当前为配置文件明文，生产应接 KMS）
- 公网部署请务必：启用 HTTPS、限制管理端访问 IP、设置请求速率限制
- 本项目仅用于合法的 AI API 网关场景，请遵守各上游服务商的条款

## 许可证

[MIT](LICENSE)




