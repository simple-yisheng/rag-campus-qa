<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { MessagePlugin } from 'tdesign-vue-next'
import { register } from '../api/auth'

const router = useRouter()
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const loading = ref(false)

async function handleRegister() {
  if (!username.value.trim()) {
    MessagePlugin.warning('请输入用户名')
    return
  }
  if (!password.value || password.value.length < 6) {
    MessagePlugin.warning('密码长度不能少于6位')
    return
  }
  if (password.value !== confirmPassword.value) {
    MessagePlugin.warning('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    const result = await register({
      username: username.value.trim(),
      password: password.value,
      confirmPassword: confirmPassword.value
    })
    localStorage.setItem('token', result.token)
    localStorage.setItem('user', JSON.stringify({ username: result.username, role: result.role }))
    MessagePlugin.success('注册成功')
    router.push('/')
  } catch (e: any) {
    MessagePlugin.error(e?.response?.data?.errorMsg || '注册失败')
  } finally {
    loading.value = false
  }
}

function goLogin() {
  router.push('/login')
}
</script>

<template>
  <div class="auth-container">
    <div class="auth-card">
      <h1 class="auth-title">创建账号</h1>
      <p class="auth-subtitle">注册后即可使用校园智答系统</p>
      <t-form :label-width="0" @submit="handleRegister">
        <t-form-item>
          <t-input
            v-model="username"
            placeholder="请输入用户名"
            clearable
            size="large"
            @enter="handleRegister"
          />
        </t-form-item>
        <t-form-item>
          <t-input
            v-model="password"
            type="password"
            placeholder="请输入密码（至少6位）"
            size="large"
            @enter="handleRegister"
          />
        </t-form-item>
        <t-form-item>
          <t-input
            v-model="confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            size="large"
            @enter="handleRegister"
          />
        </t-form-item>
        <t-form-item>
          <t-button
            theme="primary"
            size="large"
            block
            :loading="loading"
            @click="handleRegister"
          >
            注 册
          </t-button>
        </t-form-item>
      </t-form>
      <div class="auth-footer">
        <span>已有账号？</span>
        <t-link theme="primary" @click="goLogin">返回登录</t-link>
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
