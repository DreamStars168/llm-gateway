<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Sunny, Moon, SwitchButton } from '@element-plus/icons-vue'
import { clearToken } from './api/client'
import { useTheme } from './composables/useTheme'

const route = useRoute()
const router = useRouter()
const { isDark, toggle } = useTheme()

interface NavItem {
  path: string
  label: string
}

const navs: NavItem[] = [
  { path: '/dashboard', label: '概览' },
  { path: '/channels', label: '渠道' },
  { path: '/models', label: '模型路由' },
  { path: '/logs', label: '请求日志' },
  { path: '/playground', label: 'Playground' }
]

const isLogin = computed(() => route.path === '/login')

function logout(): void {
  clearToken()
  router.push('/login')
}
</script>

<template>
  <div v-if="isLogin" class="login-wrap">
    <router-view />
  </div>
  <div v-else class="layout">
    <aside class="sidebar">
      <div class="brand">
        LLM Gateway
        <small>多模型接入网关</small>
      </div>

      <router-link
        v-for="n in navs"
        :key="n.path"
        :to="n.path"
        class="nav-item"
        :class="{ active: route.path === n.path }"
      >
        {{ n.label }}
      </router-link>

      <div class="spacer" />

      <div class="theme-toggle" @click="toggle">
        <el-icon><component :is="isDark ? Sunny : Moon" /></el-icon>
        <span>{{ isDark ? '切换浅色' : '切换深色' }}</span>
      </div>

      <div class="nav-item" @click="logout">
        <el-icon style="vertical-align: -2px; margin-right: 4px"><SwitchButton /></el-icon>
        退出登录
      </div>
    </aside>

    <main class="main">
      <router-view />
    </main>
  </div>
</template>
