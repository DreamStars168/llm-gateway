import { createApp } from 'vue'
import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import './style.css'

import Login from './views/Login.vue'
import Dashboard from './views/Dashboard.vue'
import Channels from './views/Channels.vue'
import Models from './views/Models.vue'
import Logs from './views/Logs.vue'
import Playground from './views/Playground.vue'
import { getToken } from './api/client'

const routes: RouteRecordRaw[] = [
  { path: '/login', component: Login, meta: { public: true } },
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', component: Dashboard },
  { path: '/channels', component: Channels },
  { path: '/models', component: Models },
  { path: '/logs', component: Logs },
  { path: '/playground', component: Playground }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  if (!to.meta.public && !getToken()) {
    return '/login'
  }
  return true
})

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
