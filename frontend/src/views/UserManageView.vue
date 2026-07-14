<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { MessagePlugin } from 'tdesign-vue-next'
import { listUsers, createUser, updateUser, resetUserPassword, deleteUser, type UserInfo } from '../api/auth'

// ========== 用户列表（分页） ==========
const users = ref<UserInfo[]>([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const pageSizeOptions = [
  { label: '5 条/页', value: 5 },
  { label: '10 条/页', value: 10 }
]

async function fetchUsers() {
  loading.value = true
  try {
    const result = await listUsers(currentPage.value, pageSize.value)
    users.value = result.records
    total.value = result.total
  } catch {
    MessagePlugin.error('获取用户列表失败')
  } finally {
    loading.value = false
  }
}

function onPageChange(page: number) {
  currentPage.value = page
  fetchUsers()
}

function onPageSizeChange(size: number) {
  pageSize.value = size
  currentPage.value = 1
  fetchUsers()
}

// ========== 新增/编辑弹窗 ==========
const dialogVisible = ref(false)
const dialogTitle = ref('新增用户')
const isEdit = ref(false)
const editId = ref<number>(0)
const form = ref({ username: '', password: '', role: 'USER' })
const submitting = ref(false)

function openCreate() {
  dialogTitle.value = '新增用户'
  isEdit.value = false
  editId.value = 0
  form.value = { username: '', password: '', role: 'USER' }
  dialogVisible.value = true
}

function openEdit(row: UserInfo) {
  dialogTitle.value = '编辑用户'
  isEdit.value = true
  editId.value = row.id
  form.value = { username: row.username, password: '', role: row.role }
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!form.value.username.trim()) {
    MessagePlugin.warning('用户名不能为空')
    return
  }
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateUser(editId.value, { username: form.value.username, role: form.value.role })
      MessagePlugin.success('更新成功')
    } else {
      if (!form.value.password || form.value.password.length < 6) {
        MessagePlugin.warning('密码长度不能少于6位')
        submitting.value = false
        return
      }
      await createUser({ username: form.value.username, password: form.value.password, role: form.value.role })
      MessagePlugin.success('创建成功')
    }
    dialogVisible.value = false
    fetchUsers()
  } catch {
    MessagePlugin.error(isEdit.value ? '更新失败' : '创建失败')
  } finally {
    submitting.value = false
  }
}

// ========== 重置密码 ==========
const pwdDialogVisible = ref(false)
const pwdUserId = ref(0)
const pwdUsername = ref('')
const newPassword = ref('')
const pwdSubmitting = ref(false)

function openResetPwd(row: UserInfo) {
  pwdUserId.value = row.id
  pwdUsername.value = row.username
  newPassword.value = ''
  pwdDialogVisible.value = true
}

async function handleResetPwd() {
  if (!newPassword.value || newPassword.value.length < 6) {
    MessagePlugin.warning('密码长度不能少于6位')
    return
  }
  pwdSubmitting.value = true
  try {
    await resetUserPassword(pwdUserId.value, newPassword.value)
    MessagePlugin.success('密码重置成功')
    pwdDialogVisible.value = false
  } catch {
    MessagePlugin.error('密码重置失败')
  } finally {
    pwdSubmitting.value = false
  }
}

// ========== 切换状态 ==========
async function toggleStatus(row: UserInfo) {
  const newStatus = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  try {
    await updateUser(row.id, { status: newStatus })
    row.status = newStatus
    MessagePlugin.success(newStatus === 'ACTIVE' ? '已启用' : '已禁用')
  } catch {
    MessagePlugin.error('操作失败')
  }
}

// ========== 删除 ==========
async function handleDelete(id: number) {
  try {
    await deleteUser(id)
    MessagePlugin.success('已删除')
    fetchUsers()
  } catch {
    MessagePlugin.error('删除失败')
  }
}

// ========== 表格列 ==========
const columns = [
  { colKey: 'id', title: 'ID', width: 70 },
  { colKey: 'username', title: '用户名', width: 160 },
  { colKey: 'role', title: '角色', width: 90, cell: 'role' },
  { colKey: 'status', title: '状态', width: 90, cell: 'status' },
  { colKey: 'createTime', title: '注册时间', width: 170 },
  { colKey: 'action', title: '操作', width: 280, cell: 'action' }
]

function formatTime(time: string) {
  return time?.replace('T', ' ')?.substring(0, 19) || ''
}

onMounted(fetchUsers)
</script>

<template>
  <div class="um-layout">
    <header class="page-header">
      <h2>校园智答 | 用户管理</h2>
    </header>

    <main class="um-main">
      <t-card>
        <template #title>
          <div style="display:flex;align-items:center;justify-content:space-between">
            <span>用户列表（共 {{ total }} 人）</span>
            <t-button theme="primary" @click="openCreate">新增用户</t-button>
          </div>
        </template>

        <t-table
          :data="users"
          :columns="columns"
          :loading="loading"
          row-key="id"
          size="small"
          hover
          stripe
        >
          <template #role="{ row }">
            <t-tag :theme="row.role === 'ADMIN' ? 'warning' : 'primary'" variant="light" size="small">
              {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </t-tag>
          </template>
          <template #status="{ row }">
            <t-tag :theme="row.status === 'ACTIVE' ? 'success' : 'danger'" variant="light" size="small">
              {{ row.status === 'ACTIVE' ? '正常' : '禁用' }}
            </t-tag>
          </template>
          <template #createTime="{ row }">
            {{ formatTime(row.createTime) }}
          </template>
          <template #action="{ row }">
            <t-button theme="primary" size="small" variant="text" @click="openEdit(row)">编辑</t-button>
            <t-button theme="default" size="small" variant="text" @click="openResetPwd(row)">重置密码</t-button>
            <t-popconfirm :content="`确认${row.status === 'ACTIVE' ? '禁用' : '启用'}该用户？`"
              @confirm="toggleStatus(row)">
              <t-button :theme="row.status === 'ACTIVE' ? 'warning' : 'success'" size="small" variant="text">
                {{ row.status === 'ACTIVE' ? '禁用' : '启用' }}
              </t-button>
            </t-popconfirm>
            <t-popconfirm content="确认删除该用户？此操作不可恢复" @confirm="handleDelete(row.id)">
              <t-button theme="danger" size="small" variant="text"
                :disabled="row.role === 'ADMIN'">删除</t-button>
            </t-popconfirm>
          </template>
        </t-table>

        <div style="margin-top:16px;display:flex;justify-content:flex-end">
          <t-pagination
            v-model:current="currentPage"
            v-model:pageSize="pageSize"
            :total="total"
            :page-size-options="pageSizeOptions"
            show-page-size
            @current-change="onPageChange"
            @page-size-change="onPageSizeChange"
          />
        </div>
      </t-card>
    </main>

    <!-- 新增/编辑弹窗 -->
    <t-dialog v-model:visible="dialogVisible" :header="dialogTitle" width="420px"
      :confirm-btn="{ content: '保存', theme: 'primary', loading: submitting }"
      @confirm="handleSubmit">
      <t-form label-width="80px">
        <t-form-item label="用户名">
          <t-input v-model="form.username" placeholder="请输入用户名" />
        </t-form-item>
        <t-form-item v-if="!isEdit" label="密码">
          <t-input v-model="form.password" type="password" placeholder="请输入密码（至少6位）" />
        </t-form-item>
        <t-form-item label="角色">
          <t-select v-model="form.role" :options="[
            { label: '普通用户', value: 'USER' },
            { label: '管理员', value: 'ADMIN' }
          ]" />
        </t-form-item>
      </t-form>
    </t-dialog>

    <!-- 重置密码弹窗 -->
    <t-dialog v-model:visible="pwdDialogVisible" header="重置密码" width="400px"
      :confirm-btn="{ content: '确定', theme: 'primary', loading: pwdSubmitting }"
      @confirm="handleResetPwd">
      <t-form label-width="80px">
        <t-form-item label="用户">{{ pwdUsername }}</t-form-item>
        <t-form-item label="新密码">
          <t-input v-model="newPassword" type="password" placeholder="请输入新密码（至少6位）" />
        </t-form-item>
      </t-form>
    </t-dialog>
  </div>
</template>

<style scoped>
.um-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.um-main {
  flex: 1;
  overflow-y: auto;
  padding: 24px 24px 64px;
  max-width: 1100px;
  margin: 0 auto;
  width: 100%;
}
</style>
