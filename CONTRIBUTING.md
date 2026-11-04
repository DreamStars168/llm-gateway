# 贡献指南

感谢参与。提交前请确认以下几点。

## 开发环境

- JDK 17+
- Maven 3.9+
- Node.js 20+
- Docker（MySQL / Redis / RabbitMQ）

## 提交前自查

```bash
# 后端：编译 + 全部测试
mvn clean package

# 前端：类型检查 + 构建
cd gateway-console
npm run build          # 含 vue-tsc --noEmit
```

**两条都必须通过**。CI 也会执行同样命令。

## 代码规范

### 后端

- 数据面（`/v1/**`）**禁止任何阻塞调用**：不要在 Reactor 线程上做 JDBC、同步 HTTP、`Thread.sleep`
- 管理端 JDBC 必须运行在 `admin-io` 线程池
- 金额统一 `long` micro-USD 或 `BigDecimal`，**禁止 double**
- 流式转发**不得聚合**响应体
- 新增协议适配器请实现 `IngressAdapter` / `EgressAdapter` 并补夹具测试

### 前端

- 全部使用 TypeScript，`strict` 模式无错误
- 组件用 `<script setup lang="ts">`
- API 类型定义在 `src/api/types.ts`，与后端契约保持同步
- 主题适配：使用 CSS 变量（`var(--panel)` 等），不要硬编码颜色

## 提交信息

采用 Conventional Commits：

```
feat(routing): 支持按延迟动态调整权重
fix(protocol): 修复 Gemini 流式缺失 [DONE] 导致的挂起
docs(readme): 补充多实例部署说明
test(billing): 补充多模态计价边界用例
```

## 新增一个协议适配器

参考现有实现，需要三部分：

1. **Ingress**（可选）：`XxxParser` 把请求解析成 `CanonicalRequest`
2. **Egress**：`XxxRequestBuilder` 把 IR 构造成上游报文
3. **流式**：`XxxStreamDecoder` 把上游 SSE 解码成 `CanonicalChunk`

若只需支持"作为上游渠道"，做 2 + 3 即可，入口复用现有协议。

**务必**：
- 在 `Protocol` 枚举中注册
- 补一组用例到 `src/test/java/.../protocol/`
- 在 `docs/` 中记录字段映射与已知限制

## 安全

- **不要提交任何真实密钥**。`.env` 已被忽略，示例值放 `.env.example`
- 发现安全问题请通过 issue 私下联系，不要公开披露
