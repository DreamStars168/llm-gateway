<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, VideoPlay, Delete, Clock, Refresh } from '@element-plus/icons-vue'
import { api } from '../api/client'
import type { AnthropicSseEvent, PromptVersion } from '../api/types'

/** 可选模型（与后端 route 表对应） */
const AVAILABLE_MODELS = ['claude-sonnet-4', 'claude-haiku-4', 'gpt-4o-mini', 'gemini-2.5-pro'] as const
type ModelName = (typeof AVAILABLE_MODELS)[number]

interface ColumnUsage {
  input: number
  output: number
}

interface Column {
  id: number
  model: ModelName
  text: string
  reasoning: string
  running: boolean
  done: boolean
  error: string
  ttft: number | null
  total: number | null
  usage: ColumnUsage | null
  controller: AbortController | null
}

const apiKey = ref(localStorage.getItem('gw_api_key') ?? 'sk-lg-demo-key-0001')
const prompt = ref('用三句话解释什么是 SSE 流式传输。')
const systemPrompt = ref('')
const temperature = ref(0.7)
const topP = ref(1.0)
const maxTokens = ref(256)

// ── 历史版本 ──────────────────────────────────────────
const history = ref<PromptVersion[]>([])
const historyOpen = ref(false)
const historyLoading = ref(false)
const historyError = ref('')

async function loadHistory(): Promise<void> {
  historyLoading.value = true
  historyError.value = ''
  try {
    const res = await api.listPrompts({ limit: 50 })
    history.value = res.data ?? []
  } catch (e) {
    historyError.value = e instanceof Error ? e.message : String(e)
  } finally {
    historyLoading.value = false
  }
}

async function toggleHistory(): Promise<void> {
  historyOpen.value = !historyOpen.value
  if (historyOpen.value) await loadHistory()
}

async function restore(item: PromptVersion): Promise<void> {
  try {
    const d = await api.getPrompt(item.id)
    const msgs = typeof d.messages === 'string' ? JSON.parse(d.messages) : d.messages
    const params = typeof d.params === 'string' ? JSON.parse(d.params) : d.params

    const first = Array.isArray(msgs) ? msgs.find((m: { role: string }) => m.role === 'user') : null
    if (first) {
      const c = first.content
      prompt.value =
        typeof c === 'string'
          ? c
          : Array.isArray(c)
            ? c.map((x: { text?: string }) => x.text ?? '').join('')
            : ''
    }
    if (params) {
      if (params.temperature != null) temperature.value = Number(params.temperature)
      if (params.top_p != null) topP.value = Number(params.top_p)
      if (params.max_tokens != null) maxTokens.value = Number(params.max_tokens)
    }
    const col = columns.value[0]
    if (col && (AVAILABLE_MODELS as readonly string[]).includes(d.model)) {
      col.model = d.model as ModelName
    }
    historyOpen.value = false
    ElMessage.success('已回填到编辑器')
  } catch (e) {
    historyError.value = e instanceof Error ? e.message : String(e)
  }
}

async function removeHistory(item: PromptVersion): Promise<void> {
  try {
    await api.deletePrompt(item.id)
    await loadHistory()
    ElMessage.success('已删除')
  } catch (e) {
    historyError.value = e instanceof Error ? e.message : String(e)
  }
}

function fmtTime(t: string): string {
  return String(t).replace('T', ' ').slice(0, 19)
}

/** 运行成功后自动存档（失败静默，不影响对话） */
function archive(col: Column): void {
  const messages: Array<{ role: string; content: string }> = [
    { role: 'user', content: prompt.value }
  ]
  if (systemPrompt.value.trim()) {
    messages.unshift({ role: 'system', content: systemPrompt.value })
  }
  api
    .savePrompt({
      name: `${col.model} · ${new Date().toLocaleString('zh-CN')}`,
      model: col.model,
      params: {
        temperature: Number(temperature.value),
        top_p: Number(topP.value),
        max_tokens: Number(maxTokens.value)
      },
      messages
    })
    .catch(() => {
      /* 静默 */
    })
}

let colSeq = 0

function newColumn(model: ModelName): Column {
  return {
    id: ++colSeq,
    model,
    text: '',
    reasoning: '',
    running: false,
    done: false,
    error: '',
    ttft: null,
    total: null,
    usage: null,
    controller: null
  }
}

const columns = ref<Column[]>([newColumn('claude-sonnet-4'), newColumn('gpt-4o-mini')])

function addColumn(): void {
  if (columns.value.length >= 4) return
  const used = new Set(columns.value.map((c) => c.model))
  const model = AVAILABLE_MODELS.find((m) => !used.has(m)) ?? AVAILABLE_MODELS[0]
  columns.value.push(newColumn(model))
}

function removeColumn(id: number): void {
  const c = columns.value.find((x) => x.id === id)
  c?.controller?.abort()
  if (columns.value.length <= 1) return
  columns.value = columns.value.filter((x) => x.id !== id)
}

function stop(col: Column): void {
  col.controller?.abort()
  col.running = false
}

function saveKey(): void {
  localStorage.setItem('gw_api_key', apiKey.value)
}

/**
 * SSE 帧解析器。
 * fetch 的 ReadableStream 分片可能把一帧切开，必须自己缓冲重组。
 */
function makeSseParser(onEvent: (event: string | null, data: string) => void): (chunk: string) => void {
  let buf = ''
  return (chunk: string) => {
    buf += chunk
    const frames = buf.split('\n\n')
    buf = frames.pop() ?? ''
    for (const f of frames) {
      const lines = f.split('\n')
      let event: string | null = null
      const data: string[] = []
      for (const line of lines) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data.push(line.slice(5).trim())
      }
      if (data.length) onEvent(event, data.join('\n'))
    }
  }
}

async function run(col: Column): Promise<void> {
  if (col.running) return

  col.text = ''
  col.reasoning = ''
  col.error = ''
  col.ttft = null
  col.total = null
  col.usage = null
  col.done = false
  col.running = true

  const controller = new AbortController()
  col.controller = controller
  const t0 = performance.now()

  const body: Record<string, unknown> = {
    model: col.model,
    max_tokens: Number(maxTokens.value),
    temperature: Number(temperature.value),
    top_p: Number(topP.value),
    stream: true,
    messages: [{ role: 'user', content: prompt.value }]
  }
  if (systemPrompt.value.trim()) body.system = systemPrompt.value

  try {
    const res = await fetch('/v1/messages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-api-key': apiKey.value },
      body: JSON.stringify(body),
      signal: controller.signal
    })

    if (!res.ok) {
      const text = await res.text()
      col.error = `HTTP ${res.status}: ${text.slice(0, 300)}`
      return
    }
    if (!res.body) {
      col.error = '响应无 body'
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()

    const handle = makeSseParser((_event, data) => {
      if (data === '[DONE]') return
      let obj: AnthropicSseEvent
      try {
        obj = JSON.parse(data) as AnthropicSseEvent
      } catch {
        return
      }

      if (obj.type === 'content_block_delta') {
        if (col.ttft === null) col.ttft = Math.round(performance.now() - t0)
        const d = obj.delta
        if (d?.type === 'text_delta' && d.text) col.text += d.text
        else if (d?.type === 'thinking_delta' && d.thinking) col.reasoning += d.thinking
      } else if (obj.type === 'message_start' && obj.message?.usage) {
        if (!col.usage) col.usage = { input: 0, output: 0 }
        col.usage.input = obj.message.usage.input_tokens ?? 0
        col.usage.output = obj.message.usage.output_tokens ?? 0
      } else if (obj.type === 'message_delta') {
        if (!col.usage) col.usage = { input: 0, output: 0 }
        if (obj.usage) col.usage.output = obj.usage.output_tokens ?? col.usage.output
      } else if (obj.type === 'error') {
        col.error = obj.error?.message ?? 'upstream error'
      }
    })

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      handle(decoder.decode(value, { stream: true }))
    }

    col.total = Math.round(performance.now() - t0)
    col.done = true
    archive(col)
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      col.error = '已停止'
    } else {
      col.error = e instanceof Error ? e.message : String(e)
    }
  } finally {
    col.running = false
    col.controller = null
  }
}

function runAll(): void {
  columns.value.forEach((c) => void run(c))
}

function fmtTokens(u: ColumnUsage | null): string {
  if (!u) return '—'
  return `${u.input ?? 0} / ${u.output ?? 0}`
}

function fmtCost(u: ColumnUsage | null): string {
  if (!u) return '—'
  const micro = (u.input ?? 0) + (u.output ?? 0)
  return '$' + (micro / 1_000_000).toFixed(6)
}

onMounted(() => {
  saveKey()
})
</script>

<template>
  <h1>
    Playground
    <span class="dim" style="font-size: 12px; font-weight: 400">多模型并排对比 · 流式</span>
  </h1>

  <div class="toolbar">
    <el-input v-model="apiKey" placeholder="API Key" style="width: 260px" @change="saveKey" />
    <span class="dim" style="font-size: 12px">保存在本地 localStorage</span>
    <div class="spacer" />
    <el-button :icon="Clock" @click="toggleHistory">
      历史版本{{ historyOpen ? ' ▲' : ' ▼' }}
    </el-button>
    <el-button :icon="Plus" :disabled="columns.length >= 4" @click="addColumn">添加列</el-button>
    <el-button type="primary" :icon="VideoPlay" :disabled="!prompt.trim()" @click="runAll">
      全部运行
    </el-button>
  </div>

  <!-- 历史版本面板 -->
  <el-card v-if="historyOpen" shadow="never" style="margin-bottom: 16px">
    <template #header>
      <div style="display: flex; align-items: center; gap: 10px">
        <strong>Prompt 历史</strong>
        <span class="dim" style="font-size: 12px">每次运行会自动存档</span>
        <div class="spacer" />
        <el-button size="small" :icon="Refresh" @click="loadHistory">刷新</el-button>
      </div>
    </template>

    <el-alert v-if="historyError" :title="historyError" type="error" :closable="false" />

    <el-table v-loading="historyLoading" :data="history" size="small" stripe>
      <el-table-column label="时间" width="160">
        <template #default="{ row }">
          <span class="mono" style="font-size: 11px">{{ fmtTime(row.created_at) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="model" label="模型" width="150">
        <template #default="{ row }">
          <span class="mono">{{ row.model }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="messages" label="messages" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono dim" style="font-size: 11px">{{ row.messages }}</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="150" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click="restore(row)">回填</el-button>
          <el-button link type="danger" @click="removeHistory(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty><span class="dim">暂无历史记录，运行一次即可自动存档</span></template>
    </el-table>
  </el-card>

  <!-- 参数区 -->
  <el-card shadow="never" style="margin-bottom: 16px">
    <el-form label-position="top">
      <el-form-item label="System Prompt（可选）">
        <el-input
          v-model="systemPrompt"
          type="textarea"
          :rows="2"
          placeholder="例如：你是一个简洁的技术助手"
        />
      </el-form-item>

      <el-form-item label="用户消息">
        <el-input v-model="prompt" type="textarea" :rows="3" placeholder="输入要对比的问题" />
      </el-form-item>

      <el-row :gutter="24">
        <el-col :span="9">
          <el-form-item :label="`Temperature: ${Number(temperature).toFixed(2)}`">
            <el-slider v-model="temperature" :min="0" :max="2" :step="0.05" show-input />
          </el-form-item>
        </el-col>
        <el-col :span="9">
          <el-form-item :label="`Top-p: ${Number(topP).toFixed(2)}`">
            <el-slider v-model="topP" :min="0" :max="1" :step="0.01" show-input />
          </el-form-item>
        </el-col>
        <el-col :span="6">
          <el-form-item label="Max tokens">
            <el-input-number v-model="maxTokens" :min="1" :max="8192" style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
  </el-card>

  <!-- 多列对比 -->
  <el-row :gutter="12">
    <el-col v-for="col in columns" :key="col.id" :span="Math.floor(24 / columns.length)">
      <el-card shadow="never" style="margin-bottom: 12px">
        <template #header>
          <div style="display: flex; align-items: center; gap: 8px">
            <el-select v-model="col.model" :disabled="col.running" size="small" style="flex: 1">
              <el-option v-for="m in AVAILABLE_MODELS" :key="m" :label="m" :value="m" />
            </el-select>
            <el-button
              v-if="!col.running"
              size="small"
              type="primary"
              :icon="VideoPlay"
              @click="run(col)"
            >
              运行
            </el-button>
            <el-button v-else size="small" type="danger" @click="stop(col)">停止</el-button>
            <el-button
              size="small"
              :icon="Delete"
              :disabled="columns.length <= 1"
              @click="removeColumn(col.id)"
            />
          </div>
        </template>

        <!-- 指标行 -->
        <div style="display: flex; gap: 12px; font-size: 11px; margin-bottom: 10px; flex-wrap: wrap">
          <span class="dim">首字 <b class="mono" style="color: var(--accent)">{{ col.ttft ?? '—' }}</b> ms</span>
          <span class="dim">总耗时 <b class="mono">{{ col.total ?? '—' }}</b> ms</span>
          <span class="dim">Token <b class="mono">{{ fmtTokens(col.usage) }}</b></span>
          <span class="dim">成本 <b class="mono">{{ fmtCost(col.usage) }}</b></span>
        </div>

        <el-alert
          v-if="col.reasoning"
          :title="'思考过程'"
          type="info"
          :closable="false"
          style="margin-bottom: 8px"
        >
          <div style="font-size: 12px; white-space: pre-wrap; max-height: 110px; overflow-y: auto">
            {{ col.reasoning }}
          </div>
        </el-alert>

        <div
          style="min-height: 190px; max-height: 420px; overflow-y: auto; background: var(--panel-2); border-radius: 6px; padding: 11px; font-size: 13px; white-space: pre-wrap"
        >
          <span v-if="col.error" style="color: var(--err)">{{ col.error }}</span>
          <span v-else-if="!col.text && col.running" class="dim">等待首字节…</span>
          <span v-else-if="!col.text" class="dim">点击「运行」开始</span>
          <span v-else>{{ col.text }}<span v-if="col.running" style="color: var(--accent)">▌</span></span>
        </div>

        <div v-if="col.done" style="margin-top: 8px; font-size: 11px; color: var(--ok)">✓ 完成</div>
      </el-card>
    </el-col>
  </el-row>
</template>
