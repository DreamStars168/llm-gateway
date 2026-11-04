<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { api } from '../api/client'
import type { RelayLogRow } from '../api/types'

const rows = ref<RelayLogRow[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const model = ref('')
const channel = ref('')
const status = ref<number | ''>('')
const loading = ref(true)
const err = ref('')

const drawerOpen = ref(false)
const detail = ref<Record<string, unknown> | null>(null)

async function load(): Promise<void> {
  loading.value = true
  err.value = ''
  try {
    const res = await api.logs({
      page: page.value,
      size,
      model: model.value,
      channel: channel.value,
      status: status.value
    })
    rows.value = res.data ?? []
    total.value = res.total ?? 0
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function onPageChange(p: number): void {
  page.value = p
  void load()
}

async function showDetail(requestId: string): Promise<void> {
  detail.value = null
  drawerOpen.value = true
  try {
    detail.value = await api.logDetail(requestId)
  } catch (e) {
    detail.value = { error: e instanceof Error ? e.message : String(e) }
  }
}

function fmtCost(micro: number): string {
  return '$' + ((micro ?? 0) / 1_000_000).toFixed(6)
}

function fmtTime(t: string): string {
  return String(t).replace('T', ' ').slice(0, 19)
}

onMounted(() => void load())
</script>

<template>
  <h1>请求日志</h1>

  <div class="toolbar">
    <el-input v-model="model" placeholder="模型" style="width: 160px" clearable @keyup.enter="search" />
    <el-input v-model="channel" placeholder="渠道" style="width: 150px" clearable @keyup.enter="search" />
    <el-select v-model="status" placeholder="全部状态" style="width: 130px" clearable>
      <el-option label="200" :value="200" />
      <el-option label="400" :value="400" />
      <el-option label="401" :value="401" />
      <el-option label="402" :value="402" />
      <el-option label="429" :value="429" />
      <el-option label="500" :value="500" />
      <el-option label="503" :value="503" />
    </el-select>
    <el-button type="primary" :icon="Search" @click="search">查询</el-button>
    <el-button :icon="Refresh" @click="load">刷新</el-button>
    <div class="spacer" />
    <span class="dim">共 {{ total }} 条</span>
  </div>

  <el-alert v-if="err" :title="err" type="error" :closable="false" style="margin-bottom: 14px" />

  <el-table :data="rows" v-loading="loading" size="small" stripe>
    <el-table-column label="时间" width="160">
      <template #default="{ row }">
        <span class="mono" style="font-size: 11px">{{ fmtTime(row.created_at) }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="virtual_model" label="模型" min-width="140">
      <template #default="{ row }">
        <span class="mono">{{ row.virtual_model }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="channel_name" label="渠道" min-width="130">
      <template #default="{ row }">
        <span class="mono dim">{{ row.channel_name }}</span>
      </template>
    </el-table-column>
    <el-table-column label="协议" width="180">
      <template #default="{ row }">
        <el-tag size="small" type="info">{{ row.protocol_in }}→{{ row.protocol_out }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="流式" width="70" align="center">
      <template #default="{ row }">{{ row.gateway_stream ? '是' : '否' }}</template>
    </el-table-column>
    <el-table-column label="状态" width="80" align="center">
      <template #default="{ row }">
        <el-tag :type="row.status_code < 400 ? 'success' : 'danger'" size="small">
          {{ row.status_code }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="Token" width="110" align="center">
      <template #default="{ row }">{{ row.input_tokens }}/{{ row.output_tokens }}</template>
    </el-table-column>
    <el-table-column label="成本" width="120">
      <template #default="{ row }">
        <span class="mono">{{ fmtCost(row.cost_micro) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="来源" width="100" align="center">
      <template #default="{ row }">
        <el-tag :type="row.usage_source === 'UPSTREAM' ? 'success' : 'warning'" size="small">
          {{ row.usage_source }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="耗时" width="90" align="center">
      <template #default="{ row }">{{ row.total_ms }}ms</template>
    </el-table-column>
    <el-table-column label="" width="80" align="center">
      <template #default="{ row }">
        <el-button link type="primary" @click="showDetail(row.request_id)">详情</el-button>
      </template>
    </el-table-column>
    <template #empty><span class="dim">暂无日志</span></template>
  </el-table>

  <div class="toolbar mt" style="justify-content: flex-end">
    <el-pagination
      :current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next"
      @current-change="onPageChange"
    />
  </div>

  <el-drawer v-model="drawerOpen" title="请求详情" size="520px">
    <pre
      v-if="detail"
      class="mono"
      style="font-size: 12px; white-space: pre-wrap; word-break: break-all"
    >{{ JSON.stringify(detail, null, 2) }}</pre>
    <el-skeleton v-else :rows="6" animated />
  </el-drawer>
</template>
