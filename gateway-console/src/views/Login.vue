<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api, setToken } from '../api/client'

const router = useRouter()
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function submit(): Promise<void> {
  if (loading.value) return
  error.value = ''
  loading.value = true
  try {
    const res = await api.login(username.value, password.value)
    setToken(res.token)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    error.value = msg === 'UNAUTHORIZED' ? '用户名或密码错误' : msg
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-box">
    <h1>LLM Gateway 控制台</h1>

    <el-input
      v-model="username"
      placeholder="用户名"
      size="large"
      :prefix-icon="User"
      style="margin-bottom: 12px"
      @keyup.enter="submit"
    />
    <el-input
      v-model="password"
      type="password"
      placeholder="密码"
      size="large"
      :prefix-icon="Lock"
      show-password
      style="margin-bottom: 16px"
      @keyup.enter="submit"
    />

    <el-button
      type="primary"
      size="large"
      :loading="loading"
      style="width: 100%"
      @click="submit"
    >
      {{ loading ? '登录中…' : '登录' }}
    </el-button>

    <div v-if="error" class="err-text">{{ error }}</div>
    <div class="dim" style="margin-top: 14px; font-size: 12px; text-align: center">
      凭证由服务端 ADMIN_USERNAME / ADMIN_PASSWORD 环境变量配置
    </div>
  </div>
</template>
