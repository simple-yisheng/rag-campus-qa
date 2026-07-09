<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getMe } from '../api/auth'

const router = useRouter()
const route = useRoute()

interface UserInfo {
  username: string
  role: string
}

const user = ref<UserInfo | null>(null)

// 从 localStorage 恢复或从后端获取
onMounted(async () => {
  const stored = localStorage.getItem('user')
  if (stored) {
    try {
      user.value = JSON.parse(stored)
    } catch {}
  }
  // 同时从后端获取最新信息
  try {
    const info = await getMe()
    user.value = { username: info.username, role: info.role }
    localStorage.setItem('user', JSON.stringify(user.value))
  } catch {}
})

function goChat() {
  router.push('/')
}

function goDocuments() {
  router.push('/documents')
}

function handleLogout() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  router.push('/login')
}

function isActive(path: string) {
  return route.path === path
}
</script>

<template>
  <div class="layout">
    <header class="layout-header">
      <div class="header-left">
        <span class="header-logo" @click="goChat">🎓 校园智答</span>
        <nav class="header-nav">
          <t-button
            :variant="isActive('/') ? 'base' : 'text'"
            :theme="isActive('/') ? 'primary' : 'default'"
            size="small"
            @click="goChat"
          >
            智能问答
          </t-button>
          <t-button
            :variant="isActive('/documents') ? 'base' : 'text'"
            :theme="isActive('/documents') ? 'primary' : 'default'"
            size="small"
            @click="goDocuments"
          >
            文档管理
          </t-button>
        </nav>
      </div>
      <div class="header-right">
        <span class="user-info">
          {{ user?.username || '用户' }}
          <t-tag v-if="user?.role === 'ADMIN'" theme="warning" size="small" style="margin-left: 4px;">管理员</t-tag>
        </span>
        <t-button theme="default" size="small" variant="text" @click="handleLogout">退出</t-button>
      </div>
    </header>
    <main class="layout-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 24px;
}

.header-logo {
  font-size: 18px;
  font-weight: 600;
  color: #0052d9;
  cursor: pointer;
  user-select: none;
}

.header-nav {
  display: flex;
  gap: 4px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-info {
  font-size: 14px;
  color: #333;
}

.layout-main {
  flex: 1;
  display: flex;
  flex-direction: column;
}
</style>
