<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { api } from '../api/client'
import type { VirtualModelInfo } from '../api/types'

const models = ref<VirtualModelInfo[]>([])
const loading = ref(true)
const err = ref('')

async function load(): Promise<void> {
  loading.value = true
  err.value = ''
  try {
    const res = await api.models()
    models.value = res.data ?? []
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
  <h1>模型路由</h1>

  <div class="toolbar">
    <el-button :icon="Refresh" @click="load">刷新</el-button>
    <span class="dim">{{ models.length }} 个虚拟模型</span>
  </div>

  <el-alert v-if="err" :title="err" type="error" :closable="false" style="margin-bottom: 14px" />

  <el-card
    v-for="m in models"
    :key="m.name"
    shadow="never"
    style="margin-bottom: 16px"
  >
    <template #header>
      <div style="display: flex; align-items: center; gap: 10px">
        <span class="mono" style="font-weight: 600">{{ m.name }}</span>
        <el-tag size="small" type="info">{{ m.routes.length }} 条路由</el-tag>
      </div>
    </template>

    <el-table :data="m.routes" size="small" stripe>
      <el-table-column prop="priority" label="优先级" width="90" align="center" />
      <el-table-column prop="channelName" label="渠道" min-width="150">
        <template #default="{ row }">
          <span class="mono">{{ row.channelName }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="upstreamModel" label="上游模型" min-width="200">
        <template #default="{ row }">
          <span class="mono dim">{{ row.upstreamModel }}</span>
        </template>
      </el-table-column>
      <el-table-column label="协议" width="150">
        <template #default="{ row }">
          <el-tag :type="protocolTag(row.protocol)" size="small">{{ row.protocol }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="weight" label="权重" width="90" align="center" />
    </el-table>
  </el-card>

  <el-empty v-if="!models.length && !loading" description="暂无模型" />
</template>
