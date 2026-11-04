import type {
  ChannelInfo,
  LoginResponse,
  LogQuery,
  PromptVersion,
  RelayLogPage,
  SavePromptPayload,
  StatsOverview,
  TimeseriesPoint,
  ChannelStat,
  ModelStat,
  VirtualModelInfo
} from './types'

const TOKEN_KEY = 'gw_admin_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(t: string): void {
  localStorage.setItem(TOKEN_KEY, t)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/** 统一的请求封装。401 时清 token 并抛出可识别的错误。 */
async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...((options.headers as Record<string, string>) ?? {})
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const res = await fetch(path, { ...options, headers })
  if (res.status === 401) {
    clearToken()
    throw new Error('UNAUTHORIZED')
  }
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`${res.status} ${text}`)
  }
  return (await res.json()) as T
}

interface DataList<T> {
  data: T[]
}

export const api = {
  login: (username: string, password: string): Promise<LoginResponse> =>
    request<LoginResponse>('/admin/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    }),

  overview: (): Promise<{ version: number; models: number; channels: number }> =>
    request('/admin/api/v1/overview'),

  channels: (): Promise<DataList<ChannelInfo>> => request('/admin/api/v1/channels'),

  models: (): Promise<DataList<VirtualModelInfo>> => request('/admin/api/v1/models'),

  statsOverview: (): Promise<StatsOverview> => request('/admin/api/v1/stats/overview'),

  statsByModel: (): Promise<DataList<ModelStat>> => request('/admin/api/v1/stats/by-model'),

  statsByChannel: (): Promise<DataList<ChannelStat>> => request('/admin/api/v1/stats/by-channel'),

  timeseries: (): Promise<DataList<TimeseriesPoint>> => request('/admin/api/v1/stats/timeseries'),

  logs: (params: LogQuery = {}): Promise<RelayLogPage> => {
    const q = new URLSearchParams()
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') {
        q.set(k, String(v))
      }
    })
    return request(`/admin/api/v1/logs?${q.toString()}`)
  },

  logDetail: (requestId: string): Promise<Record<string, unknown>> =>
    request(`/admin/api/v1/logs/${requestId}`),

  breaker: (channelId: number, model: string): Promise<{ state: string }> =>
    request(`/admin/api/v1/breaker/${channelId}/${encodeURIComponent(model)}`),

  // ── Prompt 版本 ──
  savePrompt: (payload: SavePromptPayload): Promise<{ id: number; saved: boolean }> =>
    request('/admin/api/v1/prompts', { method: 'POST', body: JSON.stringify(payload) }),

  listPrompts: (params: { limit?: number; model?: string } = {}): Promise<
    DataList<PromptVersion> & { total: number }
  > => {
    const q = new URLSearchParams()
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== '') q.set(k, String(v))
    })
    return request(`/admin/api/v1/prompts?${q.toString()}`)
  },

  getPrompt: (id: number): Promise<PromptVersion> => request(`/admin/api/v1/prompts/${id}`),

  deletePrompt: (id: number): Promise<{ deleted: number }> =>
    request(`/admin/api/v1/prompts/${id}`, { method: 'DELETE' })
}
