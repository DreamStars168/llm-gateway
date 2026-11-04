# 运行手册

## 一、能力清单

| 能力 | 状态 |
|---|---|
| Anthropic `/v1/messages` 入口（流式 + 非流式） | ✅ |
| 原生透传（同协议，字节级） | ✅ |
| 跨协议翻译（Anthropic ↔ OpenAI Chat ↔ Gemini） | ✅ |
| API Key 鉴权（Caffeine 缓存） | ✅ |
| 虚拟模型 → 渠道路由（优先级硬分层 + SWRR） | ✅ |
| 会话亲和 Sticky（Redis + 本地两级缓存） | ✅ |
| 渠道并发闸门（不排队，打满换候选） | ✅ |
| 熔断（渠道 × 模型，10s 窗口 / 50% / 30s 冷却） | ✅ |
| 自动降权（错误率驱动，保留探测流量） | ✅ |
| 故障转移（首字节前可重试） | ✅ |
| 两阶段配额（Redis Lua 预扣 / 结算 / 释放，含 FAIL_OPEN 降级） | ✅ |
| 分级定价（阶梯 + 倍率 + 折扣 + 缓存价） | ✅ |
| 多模态计价（图片按张 / 音频按秒） | ✅ |
| 流式 usage 提取（透传嗅探 + 翻译链路事件累计） | ✅ |
| RabbitMQ 异步落库（明细 / 聚合 / 账单，幂等） | ✅ |
| Prometheus 指标 | ✅ |
| 管理端 JWT 鉴权 + 渠道/模型/日志/统计 API | ✅ |
| Vue3 + TypeScript + Element Plus 控制台 | ✅ |
| 明暗主题切换 | ✅ |
| Playground 多列流式对比（Temperature / Top-p / TTFT / 成本） | ✅ |
| Prompt 版本归档（自动存档 / 回填 / 删除） | ✅ |

---

## 二、环境变量（必读）

本项目**不提供默认密码**。缺少以下变量时应用启动失败并给出提示：

| 变量 | 必填 | 说明 |
|---|---|---|
| `ADMIN_USERNAME` | ✅ | 管理端用户名 |
| `ADMIN_PASSWORD` | ✅ | 管理端密码（>= 8 位；支持 BCrypt 哈希） |
| `ADMIN_JWT_SECRET` | ✅ | JWT 密钥（>= 32 字节，`openssl rand -hex 32`） |
| `MYSQL_PASS` | ✅ | MySQL 密码 |
| `RABBIT_PASS` | ✅ | RabbitMQ 密码 |
| `DEMO_API_KEY` | ⬜ | 演示客户端 Key，默认 `sk-lg-demo-key-local` |

可选：`SERVER_PORT`（默认 18081）、`REDIS_HOST/PORT`、`MYSQL_HOST/PORT/USER`、`RABBIT_HOST/USER`。

生成密钥示例：

```bash
export ADMIN_USERNAME=admin
export ADMIN_PASSWORD="$(openssl rand -base64 18)"
export ADMIN_JWT_SECRET="$(openssl rand -hex 32)"
export MYSQL_PASS="$(openssl rand -base64 18)"
export RABBIT_PASS="$(openssl rand -base64 18)"
```

---

## 三、启动

### 1. 依赖容器

```bash
docker run -d --name lg-redis-dev -p 6379:6379 redis:7-alpine
docker run -d --name lg-mysql -e MYSQL_ROOT_PASSWORD=<你的密码> -e MYSQL_DATABASE=gateway -p 13306:3306 mysql:8.0 --character-set-server=utf8mb4
docker run -d --name lg-rabbit -e RABBITMQ_DEFAULT_USER=gateway -e RABBITMQ_DEFAULT_PASS=<你的密码> -p 5672:5672 -p 15672:15672 rabbitmq:3.13-management-alpine
```

> MySQL 用 **13306** 映射，避免与本机已有 3306 冲突。

### 2. 建表

```bash
docker cp db/schema.sql lg-mysql:/tmp/schema.sql
docker exec lg-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" gateway < /tmp/schema.sql'
# 验证：应输出 20
docker exec lg-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='gateway';"
```

### 3. 构建

```bash
mvn -DskipTests package
```

### 4. 启动 Mock 上游

```bash
# 主渠道（同时提供 Anthropic 与 OpenAI 两种接口）
java -jar mock-upstream/target/mock-upstream-0.1.0-SNAPSHOT.jar --server.port=9000
# 备渠道（故障转移演练用）
java -jar mock-upstream/target/mock-upstream-0.1.0-SNAPSHOT.jar --server.port=9001
```

### 5. 启动网关

```bash
MYSQL_PORT=13306 java -jar gateway-server/target/gateway-server-0.1.0-SNAPSHOT.jar
```

> 默认端口 **18081**（8081 常被 Docker Desktop 等占用，`SERVER_PORT` 可覆盖）。

### 6. 初始化余额

```bash
redis-cli SET quota:1:balance 9999999999   # micro-USD，约 10000 USD
redis-cli SET quota:1:reserved 0
```

### 7. 前端控制台

```bash
cd gateway-console
npm install
npm run dev     # http://localhost:5173
```

登录：使用你在 `.env` 中设置的 `ADMIN_USERNAME` / `ADMIN_PASSWORD`

---

## 三、验证

### 流式（Anthropic 透传）

```bash
curl -N http://127.0.0.1:18081/v1/messages \
  -H "x-api-key: sk-lg-demo-key-local" \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-sonnet-4","max_tokens":20,"stream":true,
       "messages":[{"role":"user","content":"hi"}]}'
```

### 跨协议翻译（Anthropic 客户端 → OpenAI 渠道）

```bash
# 注意 model 用 gpt-4o-mini（配置里指向 OPENAI_CHAT 渠道）
curl -N http://127.0.0.1:18081/v1/messages \
  -H "x-api-key: sk-lg-demo-key-local" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","max_tokens":64,"stream":true,
       "messages":[{"role":"user","content":"hi"}]}'
# 期望：Anthropic 格式的 SSE 事件，且 message_delta 里 input/output tokens 非 0
```

### 错误路径

```bash
# 401
curl -i http://127.0.0.1:18081/v1/messages -H "x-api-key: wrong" \
  -H "Content-Type: application/json" -d '{"model":"claude-sonnet-4","messages":[]}'

# 404
curl -i http://127.0.0.1:18081/v1/messages -H "x-api-key: sk-lg-demo-key-local" \
  -H "Content-Type: application/json" -d '{"model":"no-such","messages":[]}'

# 402（把余额调小）
redis-cli SET quota:1:balance 100
curl -i http://127.0.0.1:18081/v1/messages -H "x-api-key: sk-lg-demo-key-local" \
  -H "Content-Type: application/json" -d '{"model":"claude-sonnet-4","messages":[{"role":"user","content":"hi"}]}'
```

### 管理端

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:18081/admin/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"$ADMIN_USER","password":"$ADMIN_PASS"}' | jq -r .token)

curl -s http://127.0.0.1:18081/admin/api/v1/overview -H "Authorization: Bearer $TOKEN"
curl -s http://127.0.0.1:18081/admin/api/v1/stats/overview -H "Authorization: Bearer $TOKEN"
curl -s "http://127.0.0.1:18081/admin/api/v1/logs?page=1&size=10" -H "Authorization: Bearer $TOKEN"
```

### 落库验证

```bash
docker exec lg-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
  "SELECT virtual_model,channel_name,input_tokens,output_tokens,cost_micro,usage_source FROM gateway.relay_log ORDER BY id DESC LIMIT 5;"

docker exec lg-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
  "SELECT * FROM gateway.usage_daily;"
```

### 故障转移演练

```bash
# 杀掉主渠道进程
kill <mock-primary-pid>
curl -i http://127.0.0.1:18081/v1/messages -H "x-api-key: sk-lg-demo-key-local" \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-sonnet-4","messages":[{"role":"user","content":"hi"}]}'
# 期望 HTTP 200，日志 settled 行显示 channel=anthropic-backup, priority=20
```

---

## 四、压测

```bash
# 吞吐 + 稳定性
node scripts/load-test.mjs --concurrency 200 --duration 30

# 网关附加延迟（必须分开跑，否则第二轮被第一轮污染）
node scripts/measure-overhead.mjs --mode direct  --concurrency 20 --samples 400
node scripts/measure-overhead.mjs --mode gateway --concurrency 20 --samples 400
```

实测结果见 [docs/04-运维与压测.md](docs/04-运维与压测.md)。

---

## 五、已知限制与后续工作

| 限制 | 影响 | 后续方案 |
|---|---|---|
| 配额按请求预扣，每请求 1 次 Redis RTT | 50 并发时 P99 升至 ~19ms | 余额分 available/frozen 子账户 + 租约（见报告 §4.3 复盘） |
| Lettuce reactive 单连接 | 高并发时预扣排队 | 换非 reactive 模板 + 连接池，或 Redis Cluster |
| 配置来自 yml，无热更新 | 改配置需重启 | 接 MySQL 快照 + Redis 失效广播 |
| 管理端写操作（增删渠道）未实现 | 只能看不能改 | 补 CRUD + 配置版本 |
| 仅 Anthropic / OpenAI Chat 两协议完整 | Gemini / Responses 未实现 | 补对应适配器（IR 已就绪） |
| 单实例验证 | 未验证多节点 | 需共享 Redis + 配置广播 |

---

## 六、故障速查

| 现象 | 可能原因 | 排查 |
|---|---|---|
| 启动报 `GenericObjectPoolConfig` 缺失 | 缺 commons-pool2 | 已在 pom 中声明，检查是否被覆盖 |
| 所有 POST 返回 403 CSRF | Spring Security 默认开启 CSRF | 已在 SecurityConfig 关闭 |
| 管理端 401 但 token 正常 | Security 在过滤器前拦截 | 已将 `/admin/**` 设为 permitAll，由 AdminAuthFilter 处理 |
| 落库 0 条 | MQ 消费失败进死信 | 查 `rabbitmqctl list_queues`，看 `gateway.usage.dlq` |
| SQL 报 `Invalid JSON text` | route_trace 传了非 JSON | 必须用 Jackson 序列化，不能 `toString()` |
| SQL 报语法错误 | 用了保留字 `stream` | 列名已改为 `gateway_stream` |
| 压测出现 500 | Redis 超时 | 已调至 500ms + FAIL_OPEN 降级 |
| 附加延迟测出 1.8s | 测量脚本两轮互相污染 | 用 `--mode direct/gateway` 分离测量 |




