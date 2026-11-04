/**
 * 网关 API 类型定义。
 *
 * 这些类型与后端契约一一对应，改动后端时 TypeScript 会在编译期报错，
 * 避免前后端字段漂移。
 */

// ── 通用 ──────────────────────────────────────────────

export interface ApiEnvelope<T> {
  code?: number
  message?: string
  data?: T
}

export interface LoginResponse {
  token: string
  username: string
  role: string
  expiresIn: number
}

// ── 概览 ──────────────────────────────────────────────

export interface StatsOverview {
  requests: number
  errors: number
  input_tokens: number
  output_tokens: number
  cost_micro: number
  avg_ms: number
  successRate: number
}

export interface ModelStat {
  virtual_model: string
  requests: number
  input_tokens: number
  output_tokens: number
  cost_micro: number
}

export interface ChannelStat {
  channel_name: string
  requests: number
  errors: number
  avg_ms: number
  cost_micro: number
}

export interface TimeseriesPoint {
  ts: string
  requests: number
  tokens: number
  cost_micro: number
}

// ── 渠道 / 模型 ───────────────────────────────────────

export type ChannelProtocol = 'ANTHROPIC' | 'OPENAI_CHAT' | 'OPENAI_RESPONSES' | 'GEMINI'

export interface ChannelInfo {
  id: number
  name: string
  protocol: ChannelProtocol
  baseUrl: string
  priority: number
  weight: number
  maxInflight: number
  passthrough: boolean
  enabled: boolean
}

export interface ModelRouteInfo {
  channelId: number
  channelName: string
  upstreamModel: string
  priority: number
  weight: number
  protocol: ChannelProtocol
}

export interface VirtualModelInfo {
  name: string
  routes: ModelRouteInfo[]
}

// ── 日志 ──────────────────────────────────────────────

export interface RelayLogRow {
  request_id: string
  virtual_model: string
  upstream_model: string | null
  channel_name: string | null
  protocol_in: string
  protocol_out: string
  gateway_stream: number
  status_code: number
  error_code: string | null
  input_tokens: number
  output_tokens: number
  cost_micro: number
  usage_source: 'UPSTREAM' | 'ESTIMATED'
  ttft_ms: number | null
  total_ms: number | null
  created_at: string
}

export interface RelayLogPage {
  total: number
  page: number
  size: number
  data: RelayLogRow[]
}

export interface LogQuery {
  page?: number
  size?: number
  model?: string
  channel?: string
  status?: number | ''
}

// ── Prompt 版本 ───────────────────────────────────────

export interface PromptVersion {
  id: number
  name: string | null
  model: string
  params: string
  messages: string
  tags: string | null
  parent_id: number | null
  created_at: string
  truncated?: boolean
}

export interface SavePromptPayload {
  name?: string
  model: string
  params: Record<string, unknown>
  messages: Array<{ role: string; content: string }>
  tags?: string[]
}

// ── SSE 流式事件（Anthropic 格式）───────────────────────

export interface AnthropicUsage {
  input_tokens?: number
  output_tokens?: number
}

export interface AnthropicSseEvent {
  type: string
  index?: number
  message?: {
    id: string
    model: string
    usage?: AnthropicUsage
  }
  delta?: {
    type?: string
    text?: string
    thinking?: string
  }
  usage?: AnthropicUsage
  error?: {
    type?: string
    message?: string
  }
}
