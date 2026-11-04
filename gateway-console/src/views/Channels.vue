<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { api } from '../api/client'
import type { ChannelInfo } from '../api/types'

const rows = ref<ChannelInfo[]>([])
const loading = ref(true)
const err = ref('')

async function load(): Promise<void> {
  loading.value = true
  err.value = ''
  try {
    const res = await api.channels()
    rows.value = res.data ?? []
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function protocolTag(p: string): 'primary' | 'success' | 'warning' | 'info' {
  switch (p) {
    case 'ANTHROPIC':
      return 'primary'
    case 'OPENAI_CHAT':
      return 'success'
    case 'GEMINI':
      return 'warning'
    default:
      return 'info'
  }
}

onMounted(() => void load())
</script>

<template>
  <h1>渠道</h1>

  <div class="toolbar">
    <el-button :icon="Refresh" @click="load">刷新</el-button>
    <span class="dim">{{ rows.length }} 个渠道</span>
  </div>

  <el-alert v-if="err" :title="err" type="error" :closable="false" style="margin-bottom: 14px" />

  <el-table :data="rows" v-loading="loading" stripe>
    <el-table-column prop="id" label="ID" width="70" />
    <el-table-column prop="name" label="名称" min-width="160">
      <template #default="{ row }">
        <span class="mono">{{ row.name }}</span>
      </template>
    </el-table-column>
    <el-table-column label="协议" width="150">
      <template #default="{ row }">
        <el-tag :type="protocolTag(row.protocol)" size="small">{{ row.protocol }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="baseUrl" label="Base URL" min-width="220">
      <template #default="{ row }">
        <span class="mono dim" style="font-size: 11px">{{ row.baseUrl }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="priority" label="优先级" width="90" align="center" />
    <el-table-column prop="weight" label="权重" width="80" align="center" />
    <el-table-column prop="maxInflight" label="并发上限" width="100" align="center" />
    <el-table-column label="透传" width="80" align="center">
      <template #default="{ row }">
        <el-tag :type="row.passthrough ? 'success' : 'info'" size="small">
          {{ row.passthrough ? '是' : '否' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="90" align="center">
      <template #default="{ row }">
        <el-tag :type="row.enabled ? 'success' : 'danger'" size="small">
          {{ row.enabled ? '启用' : '禁用' }}
        </el-tag>
      </template>
    </el-table-column>
    <template #empty><span class="dim">暂无渠道</span></template>
  </el-table>
</template>
