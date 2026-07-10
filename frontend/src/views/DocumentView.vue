<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { MessagePlugin } from 'tdesign-vue-next'
import { listDocuments, uploadDocument, deleteDocument, reviewDocument, type DocumentInfo } from '../api/document'

const router = useRouter()

// 当前用户角色
const isAdmin = ref(false)
try {
  const user = JSON.parse(localStorage.getItem('user') || '{}')
  isAdmin.value = user.role === 'ADMIN'
} catch { /* ignore */ }

// ========== 文档列表 ==========
const documents = ref<DocumentInfo[]>([])
const loading = ref(false)

async function fetchDocuments() {
  loading.value = true
  try {
    documents.value = await listDocuments()
  } catch {
    MessagePlugin.error('获取文档列表失败')
  } finally {
    loading.value = false
  }
}

// ========== 上传 ==========
const uploadForm = ref({
  title: '',
  category: 'POLICY',
  department: ''
})
let selectedFile: File | null = null

const categoryOptions = [
  { label: '规章制度', value: 'POLICY' },
  { label: '评奖评优', value: 'SCHOLARSHIP' },
  { label: '学业政策', value: 'ACADEMIC' },
  { label: '生活指南', value: 'GUIDE' },
  { label: '其他', value: 'OTHER' }
]

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  selectedFile = target.files?.[0] || null
}

const uploading = ref(false)
async function handleUpload() {
  if (!selectedFile) {
    MessagePlugin.warning('请选择文件')
    return
  }
  uploading.value = true
  try {
    const res = await uploadDocument(
      selectedFile,
      uploadForm.value.category,
      uploadForm.value.title || undefined,
      uploadForm.value.department || undefined
    )
    if (res.status === 'DUPLICATE') {
      MessagePlugin.warning(res.message)
    } else if (res.status === 'FAILED') {
      MessagePlugin.error(res.message)
    } else {
      MessagePlugin.success(res.message)
      // 清空表单
      uploadForm.value.title = ''
      uploadForm.value.department = ''
      selectedFile = null;
      (document.getElementById('file-input') as HTMLInputElement).value = ''
      fetchDocuments()
    }
  } catch {
    MessagePlugin.error('上传失败')
  } finally {
    uploading.value = false
  }
}

const deleting = ref(false)
async function handleDelete(id: number) {
  deleting.value = true
  try {
    await deleteDocument(id)
    MessagePlugin.success('已删除')
    fetchDocuments()
  } catch {
    MessagePlugin.error('删除失败')
  } finally {
    deleting.value = false
  }
}

const reviewing = ref(false)
async function handleReview(doc: DocumentInfo, approved: boolean) {
  reviewing.value = true
  try {
    await reviewDocument(doc.id, approved)
    MessagePlugin.success(approved ? '审核通过，文档已进入后台处理' : '已驳回')
    fetchDocuments()
  } catch {
    MessagePlugin.error('审核操作失败')
  } finally {
    reviewing.value = false
  }
}

function viewFile(doc: DocumentInfo) {
  window.open(`/api/documents/${doc.id}/file`)
}

function statusTag(status: string) {
  const map: Record<string, { label: string; theme: string }> = {
    DONE: { label: '已完成', theme: 'success' },
    PROCESSING: { label: '处理中', theme: 'primary' },
    PENDING: { label: '待处理', theme: 'warning' },
    FAILED: { label: '失败', theme: 'danger' },
    DUPLICATE: { label: '重复', theme: 'default' }
  }
  return map[status] || { label: status, theme: 'default' }
}

function reviewTag(status: string) {
  const map: Record<string, { label: string; theme: string }> = {
    APPROVED: { label: '已通过', theme: 'success' },
    PENDING: { label: '待审核', theme: 'warning' },
    REJECTED: { label: '已驳回', theme: 'danger' }
  }
  return map[status] || { label: status, theme: 'default' }
}

const columns = [
  { colKey: 'title', title: '标题', width: 220 },
  { colKey: 'category', title: '分类', width: 90 },
  { colKey: 'fileType', title: '格式', width: 65 },
  { colKey: 'status', title: '处理', width: 80, cell: 'status' },
  { colKey: 'reviewStatus', title: '审核', width: 80, cell: 'reviewStatus' },
  { colKey: 'chunkCount', title: '分块', width: 60 },
  { colKey: 'createTime', title: '上传时间', width: 155 },
  { colKey: 'action', title: '操作', width: 160, cell: 'action' }
]

function formatTime(time: string) {
  return time?.replace('T', ' ')?.substring(0, 19) || ''
}

onMounted(fetchDocuments)
</script>

<template>
  <div class="doc-layout">
    <header class="page-header">
      <h2>校园智答 | 文档管理</h2>
      <t-button variant="outline" @click="router.push('/')">← 返回对话</t-button>
    </header>

    <main class="doc-main">
      <!-- 上传区域 -->
      <t-card title="上传文档" style="margin-bottom: 24px">
        <t-form layout="inline">
          <t-form-item label="标题">
            <t-input v-model="uploadForm.title" placeholder="不填则使用文件名" style="width:200px" />
          </t-form-item>
          <t-form-item label="分类">
            <t-select v-model="uploadForm.category" :options="categoryOptions" style="width:140px" />
          </t-form-item>
          <t-form-item label="部门">
            <t-input v-model="uploadForm.department" placeholder="发布单位" style="width:160px" />
          </t-form-item>
          <t-form-item label="文件">
            <input id="file-input" type="file" accept=".pdf,.docx,.doc,.txt,.md,.markdown" @change="onFileChange" />
          </t-form-item>
          <t-form-item>
            <t-button theme="primary" @click="handleUpload" :loading="uploading">上传</t-button>
          </t-form-item>
        </t-form>
      </t-card>

      <!-- 文档列表 -->
      <t-card :title="`文档列表（共 ${documents.length} 篇）`">
        <t-table
          :data="documents"
          :columns="columns"
          :loading="loading"
          row-key="id"
          size="small"
          hover
          stripe
        >
          <template #status="{ row }">
            <t-tag :theme="statusTag(row.status).theme" variant="light" size="small">
              {{ statusTag(row.status).label }}
            </t-tag>
          </template>
          <template #reviewStatus="{ row }">
            <t-tag :theme="reviewTag(row.reviewStatus).theme" variant="light" size="small">
              {{ reviewTag(row.reviewStatus).label }}
            </t-tag>
          </template>
          <template #createTime="{ row }">
            {{ formatTime(row.createTime) }}
          </template>
          <template #action="{ row }">
            <!-- 管理员审核按钮（PENDING 文档） -->
            <template v-if="isAdmin && row.reviewStatus === 'PENDING'">
              <t-button theme="success" size="small" variant="outline"
                :loading="reviewing" @click="handleReview(row, true)">
                通过
              </t-button>
              <t-popconfirm content="确认驳回该文档？驳回后文档不可检索" @confirm="handleReview(row, false)">
                <t-button theme="danger" size="small" variant="outline" style="margin-left:4px">
                  驳回
                </t-button>
              </t-popconfirm>
            </template>
            <!-- 普通用户：待审核提示 -->
            <t-tag v-else-if="row.reviewStatus === 'PENDING'" theme="warning" variant="light" size="small">
              等待审核
            </t-tag>
            <!-- 已驳回提示 -->
            <t-tag v-else-if="row.reviewStatus === 'REJECTED'" theme="danger" variant="light" size="small">
              已驳回
            </t-tag>
            <!-- 查看原文（已完成处理） -->
            <t-link v-if="row.status === 'DONE'" theme="primary" hover="color"
              @click="viewFile(row)" style="margin-left:4px">
              原文
            </t-link>
            <t-popconfirm content="确认删除该文档？" @confirm="handleDelete(row.id)">
              <t-link theme="danger" hover="color" style="margin-left:8px">删除</t-link>
            </t-popconfirm>
          </template>
        </t-table>
      </t-card>
    </main>
  </div>
</template>

<style scoped>
.doc-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.doc-main {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  max-width: 1100px;
  margin: 0 auto;
  width: 100%;
}

input[type="file"] {
  font-size: 13px;
}
</style>
