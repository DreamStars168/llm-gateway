import { ref, computed, watch } from 'vue'

export type ThemeMode = 'dark' | 'light'

const STORAGE_KEY = 'gw_theme'

/** 从 localStorage 读初始主题，缺省用深色（原设计）。 */
function initial(): ThemeMode {
  const saved = localStorage.getItem(STORAGE_KEY)
  return saved === 'light' ? 'light' : 'dark'
}

const mode = ref<ThemeMode>(initial())

/** 应用主题到 <html>，Element Plus 靠 html.dark 切换暗色变量。 */
function apply(m: ThemeMode): void {
  const root = document.documentElement
  if (m === 'dark') {
    root.classList.add('dark')
  } else {
    root.classList.remove('dark')
  }
  root.setAttribute('data-theme', m)
  localStorage.setItem(STORAGE_KEY, m)
}

// 首次立即应用，避免闪白
apply(mode.value)

watch(mode, (m) => apply(m))

export function useTheme() {
  const isDark = computed(() => mode.value === 'dark')

  function toggle(): void {
    mode.value = mode.value === 'dark' ? 'light' : 'dark'
  }

  function set(m: ThemeMode): void {
    mode.value = m
  }

  return { mode, isDark, toggle, set }
}
