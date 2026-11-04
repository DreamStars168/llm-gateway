<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import * as echarts from 'echarts'
import { api } from '../api/client'
import { useTheme } from '../composables/useTheme'
import type { StatsOverview, ModelStat, ChannelStat, TimeseriesPoint } from '../api/types'

const stats = ref<StatsOverview>({
  requests: 0,
  errors: 0,
  input_tokens: 0,
  output_tokens: 0,
  cost_micro: 0,
  avg_ms: 0,
  successRate: 1
})
const byModel = ref<ModelStat[]>([])
const byChannel = ref<ChannelStat[]>([])
const loading = ref(true)
const err = ref('')

const { isDark } = useTheme()

const chartEl = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let timer: number | null = null
let lastSeries: TimeseriesPoint[] = []

function fmtCost(micro: number): string {
  return '$' + (micro / 1_000_000).toFixed(6)
}

function fmtNum(n: number | undefined): string {
  return Number(n ?? 0).toLocaleString()
}

async function load(): Promise<void> {
  loading.value = true
  err.value = ''
  try {
    const [s, m, c, ts] = await Promise.all([
      api.statsOverview(),
      api.statsByModel(),
      api.statsByChannel(),
      api.timeseries()
    ])
    stats.value = s
    byModel.value = m.data ?? []
    byChannel.value = c.data ?? []
    lastSeries = ts.data ?? []
    await nextTick()
    renderChart()
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function renderChart(): void {
  if (!chartEl.value) return
  if (!chart) {
    chart = echarts.init(chartEl.value)
  }

  const axisColor = isDark.value ? '#8b95a5' : '#909399'
  const splitColor = isDark.value ? '#1e242c' : '#f0f2f5'

  chart.setOption(
    {
      backgroundColor: 'transparent',
      tooltip: { trigger: 'axis' },
      legend: { data: ['请求数', 'Token'], textStyle: { color: axisColor }, top: 0 },
      grid: { left: 50, right: 50, top: 40, bottom: 30 },
      xAxis: {
        type: 'category',
        data: lastSeries.map((r) => r.ts),
        axisLabel: { color: axisColor, fontSize: 11 },
        axisLine: { lineStyle: { color: splitColor } }
      },
      yAxis: [
        {
          type: 'value',
          axisLabel: { color: axisColor, fontSize: 11 },
          splitLine: { lineStyle: { color: splitColor } }
        },
        {
          type: 'value',
          axisLabel: { color: axisColor, fontSize: 11 },
          splitLine: { show: false }
        }
      ],
      series: [
        {
          name: '请求数',
          type: 'line',
          smooth: true,
          data: lastSeries.map((r) => r.requests),
          itemStyle: { color: '#4d9fff' },
          areaStyle: { opacity: 0.12 }
        },
        {
          name: 'Token',
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          data: lastSeries.map((r) => r.tokens),
          itemStyle: { color: '#3ecf8e' }
        }
      ]
    },
    true
  )
}

// 主题切换时重绘（ECharts 不会自动跟随 CSS 变量）
watch(isDark, () => {
  renderChart()
})

function onResize(): void {
  chart?.resize()
}

onMounted(() => {
  void load()
  timer = window.setInterval(() => void load(), 15000)
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <h1>
    概览
    <span class="dim" style="font-size: 12px; font-weight: 400">近 24 小时 · 每 15s 刷新</span>
  </h1>

  <el-alert v-if="err" :title="err" type="error" :closable="false" style="margin-bottom: 14px" />

  <div class="cards" v-loading="loading">
    <div class="card">
      <div class="label">请求总数</div>
      <div class="value">{{ fmtNum(stats.requests) }}</div>
    </div>
    <div class="card">
      <div class="label">成功率</div>
      <div class="value" :style="{ color: stats.successRate >= 0.95 ? 'var(--ok)' : 'var(--warn)' }">
        {{ (stats.successRate * 100).toFixed(1) }}%
      </div>
    </div>
    <div class="card">
      <div class="label">输入 / 输出 Token</div>
      <div class="value small">{{ fmtNum(stats.input_tokens) }} / {{ fmtNum(stats.output_tokens) }}</div>
    </div>
    <div class="card">
      <div class="label">累计成本</div>
      <div class="value small">{{ fmtCost(stats.cost_micro) }}</div>
    </div>
    <div class="card">
      <div class="label">平均耗时</div>
      <div class="value small">{{ Number(stats.avg_ms ?? 0).toFixed(0) }} ms</div>
    </div>
  </div>

  <h2>请求趋势</h2>
  <div
    ref="chartEl"
    style="width: 100%; height: 260px; background: var(--panel); border: 1px solid var(--border); border-radius: 8px"
  ></div>

  <el-row :gutter="16" style="margin-top: 18px">
    <el-col :span="12">
      <h2>按模型</h2>
      <el-table :data="byModel" size="small" stripe>
        <el-table-column prop="virtual_model" label="模型" min-width="150">
          <template #default="{ row }">
            <span class="mono">{{ row.virtual_model }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="requests" label="请求" width="90" />
        <el-table-column label="Token" width="120">
          <template #default="{ row }">
            {{ fmtNum(Number(row.input_tokens) + Number(row.output_tokens)) }}
          </template>
        </el-table-column>
        <el-table-column label="成本" width="130">
          <template #default="{ row }">
            <span class="mono">{{ fmtCost(row.cost_micro) }}</span>
          </template>
        </el-table-column>
        <template #empty><span class="dim">暂无数据</span></template>
      </el-table>
    </el-col>

    <el-col :span="12">
      <h2>按渠道</h2>
      <el-table :data="byChannel" size="small" stripe>
        <el-table-column prop="channel_name" label="渠道" min-width="140">
          <template #default="{ row }">
            <span class="mono">{{ row.channel_name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="requests" label="请求" width="90" />
        <el-table-column prop="errors" label="错误" width="80" />
        <el-table-column label="平均耗时" width="110">
          <template #default="{ row }">{{ Number(row.avg_ms ?? 0).toFixed(0) }} ms</template>
        </el-table-column>
        <template #empty><span class="dim">暂无数据</span></template>
      </el-table>
    </el-col>
  </el-row>
</template>
