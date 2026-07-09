<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { MessagePlugin } from 'tdesign-vue-next'
import { login } from '../api/auth'

const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)

async function handleLogin() {
  if (!username.value.trim()) {
    MessagePlugin.warning('请输入用户名')
    return
  }
  if (!password.value) {
    MessagePlugin.warning('请输入密码')
    return
  }
  loading.value = true
  try {
    const result = await login({ username: username.value.trim(), password: password.value })
    localStorage.setItem('token', result.token)
    localStorage.setItem('user', JSON.stringify({ username: result.username, role: result.role }))
    MessagePlugin.success('登录成功')
    router.push('/')
  } catch (e: any) {
    MessagePlugin.error(e?.response?.data?.errorMsg || '登录失败')
  } finally {
    loading.value = false
  }
}

function goRegister() {
  router.push('/register')
}
</script>

<template>
  <div class="auth-container">
    <div class="auth-card">
      <h1 class="auth-title">校园智答</h1>
      <p class="auth-subtitle">基于 RAG 的校园知识库问答系统</p>
      <t-form :label-width="0" @submit="handleLogin">
        <t-form-item>
          <t-input
            v-model="username"
            placeholder="请输入用户名"
            clearable
            size="large"
            @enter="handleLogin"
          />
        </t-form-item>
        <t-form-item>
          <t-input
            v-model="password"
            type="password"
            placeholder="请输入密码"
            size="large"
            @enter="handleLogin"
          />
        </t-form-item>
        <t-form-item>
          <t-button
            theme="primary"
            size="large"
            block
            :loading="loading"
            @click="handleLogin"
          >
            登 录
          </t-button>
        </t-form-item>
      </t-form>
      <div class="auth-footer">
        <span>还没有账号？</span>
        <t-link theme="primary" @click="goRegister">立即注册</t-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.auth-card {
  width: 400px;
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
}

.auth-title {
  text-align: center;
  font-size: 28px;
  color: #1a1a1a;
  margin-bottom: 8px;
}

.auth-subtitle {
  text-align: center;
  font-size: 14px;
  color: #999;
  margin-bottom: 32px;
}

.auth-footer {
  text-align: center;
  font-size: 14px;
  color: #666;
  margin-top: 8px;
}
</style>
