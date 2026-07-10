<script setup lang="ts">
import { ref, nextTick, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { MessagePlugin } from 'tdesign-vue-next'
import { askStream, getHistory, getSessions, type AskResponse, type SourceInfo } from '../api/chat'
import { getDocumentContent, type DocumentContent } from '../api/document'
import PdfViewer from '../components/PdfViewer.vue'
import { marked } from 'marked'

const router = useRouter()

// ========== 侧边栏 ==========
const sidebarOpen = ref(false)

// ========== 会话管理 ==========
interface Conversation {
  id: string
  title: string
}

const conversations = ref<Conversation[]>([])
const activeId = ref<string>('')

// ========== 消息管理 ==========
interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  sources?: AskResponse['sources']
}

const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const loading = ref(false)
const streaming = ref(false)
const messageContainer = ref<HTMLElement | null>(null)
const messageCache = new Map<string, ChatMessage[]>()
const switching = ref(false)

async function switchConversation(id: string) {
  if (activeId.value) {
    messageCache.set(activeId.value, [...messages.value])
  }
  activeId.value = id
  sidebarOpen.value = false

  const cached = messageCache.get(id)
  if (cached && cached.length > 0) {
    messages.value = cached
    scrollToBottom()
    return
  }

  switching.value = true
  try {
    const history = await getHistory(id)
    const list: ChatMessage[] = []
    for (const item of history) {
      list.push({ role: 'user', content: item.question })
      let sources: AskResponse['sources'] | undefined
      try { if (item.sources) sources = JSON.parse(item.sources) } catch {}
      list.push({ role: 'assistant', content: item.answer, sources })
    }
    messages.value = list
    messageCache.set(id, list)
  } catch {
    messages.value = []
  } finally {
    switching.value = false
    scrollToBottom()
  }
}

function newConversation() {
  if (activeId.value) {
    messageCache.set(activeId.value, [...messages.value])
  }
  activeId.value = ''
  messages.value = []
  sidebarOpen.value = false
}

function deleteConversation(id: string) {
  conversations.value = conversations.value.filter(c => c.id !== id)
  messageCache.delete(id)
  if (activeId.value === id) {
    activeId.value = ''
    messages.value = []
  }
}

// ========== 发送消息 ==========
async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  const isNew = !activeId.value
  inputText.value = ''
  messages.value.push({ role: 'user', content: text })

  // 先插入一条空的 assistant 消息，后续逐字填充
  const msgIndex = messages.value.length
  messages.value.push({ role: 'assistant', content: '', sources: [] })
  scrollToBottom()

  loading.value = true
  streaming.value = true

  await askStream(
    isNew ? '' : activeId.value,
    text,
    {
      onToken(token: string) {
        if (streaming.value) streaming.value = false
        messages.value[msgIndex].content += token
        scrollToBottom()
      },
      onSources(sessionId: string, sources: SourceInfo[]) {
        messages.value[msgIndex].sources = sources
        // 新对话：用后端返回的 sessionId 创建会话
        if (isNew && sessionId) {
          activeId.value = sessionId
          const title = text.length > 15 ? text.slice(0, 15) + '...' : text
          conversations.value.unshift({ id: sessionId, title })
        }
      },
      onDone(fullAnswer: string) {
        messages.value[msgIndex].content = fullAnswer
        streaming.value = false
        loading.value = false
        scrollToBottom()
      },
      onError(msg: string) {
        streaming.value = false
        if (messages.value[msgIndex].content) {
          messages.value[msgIndex].content += '\n\n*[流中断: ' + msg + ']*'
        } else {
          messages.value[msgIndex].content = '抱歉，请求失败: ' + msg
        }
        loading.value = false
        if (isNew) {
          messages.value.pop()
          messages.value.pop()
        }
      }
    }
  )
}

function scrollToBottom() {
  nextTick(() => {
    if (messageContainer.value) {
      messageContainer.value.scrollTop = messageContainer.value.scrollHeight
    }
  })
}

// ========== 参考资料抽屉 ==========
const drawerVisible = ref(false)
const drawerLoading = ref(false)
const drawerDoc = ref<DocumentContent | null>(null)
const drawerPdfSource = ref<AskResponse['sources'][number] | null>(null)
const drawerMode = ref<'pdf' | 'text'>('text')
const drawerHighlightSnippet = ref('')
const drawerTargetChunkIndex = ref<number | null>(null)
const drawerContentRef = ref<HTMLElement | null>(null)
const drawerDocumentId = computed(() => drawerPdfSource.value?.documentId || drawerDoc.value?.documentId)
const isDrawerMd = computed(() => drawerDoc.value?.fileType === 'MD')

async function viewSource(src: AskResponse['sources'][number]) {
  drawerVisible.value = true
  drawerLoading.value = true
  drawerDoc.value = null
  drawerPdfSource.value = null
  drawerHighlightSnippet.value = src.snippet || ''
  drawerTargetChunkIndex.value = typeof src.chunkIndex === 'number' ? src.chunkIndex : null

  const isPdf = src.fileType === 'PDF'
  const isWord = src.fileType === 'DOCX' || src.fileType === 'DOC'

  // PDF 或 Word 文档 → 使用 PdfViewer（后端已通过 LibreOffice 将 Word 转为 PDF 预览）
  if (isPdf || isWord) {
    drawerMode.value = 'pdf'
    drawerPdfSource.value = src
    drawerLoading.value = false
    return
  }

  // TXT / MD 等纯文本文档 → 文本降级模式
  drawerMode.value = 'text'
  try {
    drawerDoc.value = await getDocumentContent(src.documentId)
  } catch {
    MessagePlugin.error('获取文档内容失败')
    drawerVisible.value = false
  } finally {
    drawerLoading.value = false
  }
  if (drawerVisible.value && drawerDoc.value) {
    await nextTick()
    setTimeout(scrollToTargetChunk, 80)
  }
}

function normalizeText(text: string): string {
  return text.replace(/\s+/g, '')
}

function getTargetText(): string {
  if (!drawerDoc.value) return drawerHighlightSnippet.value
  if (drawerTargetChunkIndex.value !== null) {
    const chunk = drawerDoc.value.chunks?.find(item => item.chunkIndex === drawerTargetChunkIndex.value)
    if (chunk?.text) return chunk.text
  }
  return drawerHighlightSnippet.value
}

function findNormalizedRange(text: string, target: string): { start: number; end: number } | null {
  const normalizedTarget = normalizeText(target)
  if (normalizedTarget.length < 8) return null

  let normalizedText = ''
  const rawIndexes: number[] = []
  for (let i = 0; i < text.length; i++) {
    if (!/\s/.test(text[i])) {
      normalizedText += text[i]
      rawIndexes.push(i)
    }
  }

  const candidates = [
    normalizedTarget,
    normalizedTarget.substring(0, 180),
    normalizedTarget.substring(0, 120),
    normalizedTarget.substring(0, 80),
    normalizedTarget.substring(0, 40),
    normalizedTarget.substring(0, 24),
  ].filter(item => item.length >= 8)

  for (const candidate of candidates) {
    const normalizedStart = normalizedText.indexOf(candidate)
    if (normalizedStart >= 0) {
      const rawStart = rawIndexes[normalizedStart]
      const rawEnd = rawIndexes[normalizedStart + candidate.length - 1] + 1
      return { start: rawStart, end: rawEnd }
    }
  }

  return null
}

const drawerContentSegments = computed(() => {
  const content = drawerDoc.value?.content || ''
  const range = findNormalizedRange(content, getTargetText())
  if (!range) return [{ text: content, highlight: false }]
  return [
    { text: content.slice(0, range.start), highlight: false },
    { text: content.slice(range.start, range.end), highlight: true },
    { text: content.slice(range.end), highlight: false },
  ].filter(segment => segment.text.length > 0)
})

function scrollToTargetChunk() {
  const target = drawerContentRef.value?.querySelector('.source-hit')
  target?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function downloadDrawerDocument() {
  if (!drawerDocumentId.value) return
  window.open(`/api/documents/${drawerDocumentId.value}/file?download=true`, '_blank')
}


// ========== 工具 ==========
// ========== Markdown 渲染（基于 marked，支持表格/代码块等） ==========
function renderMarkdown(text: string): string {
  if (!text) return ''
  return marked.parse(text, { breaks: true, gfm: true }) as string
}

onMounted(async () => {
  try {
    const sessions = await getSessions()
    if (sessions.length > 0) {
      conversations.value = sessions.map(s => ({
        id: s.sessionId,
        title: s.title || '历史对话'
      }))
    }
  } catch {
    // 后端不可用，保持空列表
  }

  if (conversations.value.length > 0) {
    activeId.value = conversations.value[0].id
    switchConversation(activeId.value)
  }
})

const showWelcome = computed(() => !loading && !switching.value && messages.value.length === 0)
const hasConversations = computed(() => conversations.value.length > 0)

const suggestedQuestions = [
  '奖学金评定标准是什么？',
  '选课流程是怎样的？',
  '宿舍管理规定有哪些？',
  '如何申请转专业？',
  '医保报销流程是什么？'
]
</script>

<template>
  <div class="ds-layout">
    <!-- ==================== 侧边栏遮罩 ==================== -->
    <div v-if="sidebarOpen" class="ds-overlay" @click="sidebarOpen = false" />

    <!-- ==================== 侧边栏抽屉 ==================== -->
    <aside class="ds-sidebar" :class="{ open: sidebarOpen }">
      <div class="ds-sidebar-header">
        <span class="ds-sidebar-title">校园智答</span>
        <t-button variant="text" size="small" @click="sidebarOpen = false">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6L6 18M6 6l12 12"/></svg>
        </t-button>
      </div>

      <t-button block theme="primary" variant="outline" @click="newConversation" style="margin-bottom:16px">
        + 新建对话
      </t-button>

      <div class="ds-conv-list" v-if="hasConversations">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="ds-conv-item"
          :class="{ active: conv.id === activeId }"
          @click="switchConversation(conv.id)"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0">
            <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/>
          </svg>
          <span class="ds-conv-title">{{ conv.title }}</span>
          <t-popconfirm content="删除？" @confirm="deleteConversation(conv.id)">
            <t-button variant="text" size="small" shape="square" class="ds-conv-del">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/></svg>
            </t-button>
          </t-popconfirm>
        </div>
      </div>
      <div v-else style="color:#999;font-size:13px;text-align:center;padding:20px">
        暂无对话记录
      </div>

      <div class="ds-sidebar-footer">
        <t-button variant="text" block @click="router.push('/documents')">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:6px"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
          文档管理
        </t-button>
      </div>
    </aside>

    <!-- ==================== 主体 ==================== -->
    <div class="ds-main">
      <!-- 顶部栏 -->
      <header class="ds-topbar">
        <div class="ds-topbar-left">
          <t-button variant="text" shape="square" @click="sidebarOpen = true">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
          </t-button>
          <span class="ds-logo" @click="activeId='';messages=[]">校园智答</span>
        </div>
        <div class="ds-topbar-right">
          <t-button variant="text" @click="newConversation">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            新对话
          </t-button>
        </div>
      </header>

      <!-- ========== 对话区域 ========== -->
      <div class="ds-chat-area" ref="messageContainer">
        <!-- 欢迎页 -->
        <div v-if="showWelcome" class="ds-welcome">
          <div class="ds-welcome-logo">
            <svg width="52" height="52" viewBox="0 0 24 24" fill="none" stroke="#0052d9" stroke-width="1.5">
              <path d="M12 2L2 7l10 5 10-5-10-5z"/>
              <path d="M2 17l10 5 10-5"/>
              <path d="M2 12l10 5 10-5"/>
            </svg>
          </div>
          <h1>校园智答</h1>
          <p class="ds-welcome-sub">基于 RAG 知识库的校园智能问答助手</p>

          <!-- 建议问题 -->
          <div class="ds-suggestions">
            <button
              v-for="q in suggestedQuestions"
              :key="q"
              class="ds-suggest-btn"
              @click="inputText = q; sendMessage()"
            >
              {{ q }}
            </button>
          </div>

          <!-- 输入框 -->
          <div class="ds-input-wrapper ds-input-welcome">
            <textarea
              v-model="inputText"
              class="ds-textarea"
              placeholder="输入你的校园问题..."
              rows="1"
              @keydown.enter.exact.prevent="sendMessage"
              @input="(e: Event) => { const t = e.target as HTMLTextAreaElement; t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 160) + 'px' }"
              :disabled="loading"
            />
            <button
              class="ds-send-btn"
              :class="{ active: inputText.trim() && !loading }"
              @click="sendMessage"
              :disabled="!inputText.trim() || loading"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>
            </button>
          </div>
        </div>

        <!-- 消息列表 -->
        <template v-if="!showWelcome">
          <div v-if="switching" class="ds-loading-hint">
            <t-loading size="small" text="加载历史记录..." />
          </div>

          <div v-for="(msg, i) in messages" :key="i" class="ds-message" :class="msg.role">
            <div class="ds-msg-inner">
              <div class="ds-msg-avatar">
                <template v-if="msg.role === 'user'">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
                </template>
                <template v-else>
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
                </template>
              </div>
              <div class="ds-msg-body">
                <div v-if="msg.role === 'assistant'" class="ds-msg-text" v-html="renderMarkdown(msg.content)" />
                <div v-else class="ds-msg-text">{{ msg.content }}</div>

                <!-- 参考资料 -->
                <div v-if="msg.sources && msg.sources.length > 0" class="ds-sources">
                  <div class="ds-sources-title">📎 参考资料</div>
                  <div v-for="(src, j) in msg.sources" :key="j" class="ds-source-item">
                    <t-link theme="primary" hover="color" @click="viewSource(src)">
                      《{{ src.title }}》
                    </t-link>
                    <span class="ds-source-score">相关度 {{ (src.score * 100).toFixed(0) }}%</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 加载中（首 token 到达前） -->
          <div v-if="loading && !streaming" class="ds-message assistant">
            <div class="ds-msg-inner">
              <div class="ds-msg-avatar">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
              </div>
              <div class="ds-msg-body">
                <t-loading size="small" text="思考中..." />
              </div>
            </div>
          </div>

          <div class="ds-message-spacer" />
        </template>
      </div>

      <!-- ========== 底部输入栏（有对话时显示） ========== -->
      <div v-if="!showWelcome" class="ds-input-bar">
        <div class="ds-input-wrapper">
          <textarea
            v-model="inputText"
            class="ds-textarea"
            placeholder="输入你的问题，Enter 发送..."
            rows="1"
            @keydown.enter.exact.prevent="sendMessage"
            @input="(e: Event) => { const t = e.target as HTMLTextAreaElement; t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 160) + 'px' }"
            :disabled="loading"
          />
          <button
            class="ds-send-btn"
            :class="{ active: inputText.trim() && !loading }"
            @click="sendMessage"
            :disabled="!inputText.trim() || loading"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>
          </button>
        </div>
        <p class="ds-disclaimer">校园智答 AI 助手，答案仅供参考，请以学校官方文件为准</p>
      </div>
    </div>
  </div>

  <!-- ==================== 参考资料抽屉 ==================== -->
  <t-drawer
    v-model:visible="drawerVisible"
    size="700px"
    :footer="false"
    :close-btn="true"
  >
    <template #header>
      <div class="source-drawer-header">
        <span class="source-drawer-title">{{ drawerPdfSource?.title || drawerDoc?.title || '文档内容' }}</span>
        <t-button
          v-if="drawerDocumentId"
          size="small"
          variant="outline"
          @click="downloadDrawerDocument"
        >
          下载原文件
        </t-button>
      </div>
    </template>

    <t-loading :loading="drawerLoading" text="加载中...">
      <PdfViewer
        v-if="drawerMode === 'pdf' && drawerPdfSource && !drawerLoading"
        :file-url="`/api/documents/${drawerPdfSource.documentId}/preview`"
        :highlight-text="drawerPdfSource.snippet"
        :initial-page="drawerPdfSource.pageStart"
        :page-end="drawerPdfSource.pageEnd"
      />

      <div v-else-if="drawerDoc && !drawerLoading" ref="drawerContentRef" class="source-drawer-body">
        <div class="source-drawer-meta">
          <span>全文内容 <t-tag v-if="isDrawerMd" size="small" theme="primary" variant="light">Markdown</t-tag></span>
          <span v-if="drawerTargetChunkIndex !== null">定位片段 #{{ drawerTargetChunkIndex + 1 }}</span>
        </div>

        <!-- Markdown 文件：marked 渲染为 HTML -->
        <div v-if="isDrawerMd && drawerDoc.content" class="source-document-text source-md" v-html="renderMarkdown(drawerDoc.content)" />

        <!-- 纯文本文件：原样展示 + 关键词高亮 -->
        <div v-else-if="drawerDoc.content" class="source-document-text">
          <template v-for="(segment, index) in drawerContentSegments" :key="index">
            <mark v-if="segment.highlight" class="source-hit">{{ segment.text }}</mark>
            <span v-else>{{ segment.text }}</span>
          </template>
        </div>

        <div v-else class="source-empty">
          暂无可展示的全文内容
        </div>
      </div>
    </t-loading>
  </t-drawer>
</template>

<style scoped>
/* ==================== 全局布局 ==================== */
.ds-layout {
  display: flex;
  height: 100vh;
  background: #fdfdfd;
  color: #1a1a1a;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* ==================== 遮罩 ==================== */
.ds-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.3);
  z-index: 99;
}

/* ==================== 侧边栏 ==================== */
.ds-sidebar {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  width: 17%;
  min-width: 240px;
  background: #f9fafb;
  border-right: 1px solid #e5e7eb;
  z-index: 100;
  display: flex;
  flex-direction: column;
  padding: 16px;
  transform: translateX(-100%);
  transition: transform 0.25s ease;
}

.ds-sidebar.open {
  transform: translateX(0);
}

.ds-sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.ds-sidebar-title {
  font-size: 16px;
  font-weight: 700;
  color: #0052d9;
}

.ds-conv-list {
  flex: 1;
  overflow-y: auto;
  margin-bottom: 12px;
}

.ds-conv-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 2px;
  color: #555;
  font-size: 13px;
  transition: background 0.15s;
}

.ds-conv-item:hover { background: #eef0f2; }
.ds-conv-item.active { background: #e5edff; color: #0052d9; }

.ds-conv-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ds-conv-del { opacity: 0; }
.ds-conv-item:hover .ds-conv-del { opacity: 0.6; }

.ds-sidebar-footer {
  border-top: 1px solid #e5e7eb;
  padding-top: 12px;
}

/* ==================== 主体 ==================== */
.ds-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  position: relative;
}

/* ==================== 顶部栏 ==================== */
.ds-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 52px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
  background: #fff;
}

.ds-topbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ds-logo {
  font-size: 16px;
  font-weight: 700;
  color: #0052d9;
  cursor: pointer;
  user-select: none;
}

.ds-topbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ==================== 对话区域 ==================== */
.ds-chat-area {
  flex: 1;
  overflow-y: auto;
}

/* ==================== 欢迎页 ==================== */
.ds-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 60px 24px 40px;
}

.ds-welcome-logo {
  width: 72px;
  height: 72px;
  border-radius: 18px;
  background: #e8f3ff;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
}

.ds-welcome h1 {
  font-size: 28px;
  font-weight: 700;
  color: #1a1a1a;
  margin: 0 0 8px;
}

.ds-welcome-sub {
  font-size: 15px;
  color: #999;
  margin: 0 0 32px;
}

/* 建议问题 */
.ds-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-bottom: 32px;
}

.ds-suggest-btn {
  padding: 8px 16px;
  border: 1px solid #e0e0e0;
  border-radius: 20px;
  background: #fff;
  color: #555;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}

.ds-suggest-btn:hover {
  border-color: #0052d9;
  color: #0052d9;
  background: #f5f8ff;
}

/* ==================== 输入框 ==================== */
.ds-input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-radius: 12px;
  padding: 8px 12px;
  transition: border-color 0.15s;
  width: 100%;
}

.ds-input-wrapper:focus-within {
  border-color: #0052d9;
  box-shadow: 0 0 0 3px rgba(0,82,217,0.08);
}

.ds-input-welcome {
  max-width: 720px;
  width: 100%;
}

.ds-textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  font-size: 15px;
  line-height: 1.5;
  font-family: inherit;
  color: #1a1a1a;
  background: transparent;
  padding: 4px 0;
}

.ds-textarea::placeholder { color: #bbb; }

.ds-send-btn {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  border: none;
  background: #e8e8e8;
  color: #bbb;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.15s;
}

.ds-send-btn.active {
  background: #0052d9;
  color: #fff;
}

.ds-send-btn.active:hover {
  background: #0045b8;
}

/* ==================== 底部输入栏 ==================== */
.ds-input-bar {
  padding: 12px 24px 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  border-top: 1px solid #f0f0f0;
  background: #fff;
}

.ds-input-bar .ds-input-wrapper {
  max-width: 720px;
  width: 100%;
  box-sizing: border-box;
}

.ds-disclaimer {
  font-size: 11px;
  color: #bbb;
  margin: 6px 0 0;
  text-align: center;
  max-width: 720px;
}

/* ==================== 消息 ==================== */
.ds-message {
  padding: 12px 0;
}

.ds-msg-inner {
  display: flex;
  gap: 12px;
  max-width: 720px;
  margin: 0 auto;
  padding: 0 24px;
  width: 100%;
  box-sizing: border-box;
}

.ds-msg-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}

.ds-message.assistant .ds-msg-avatar {
  background: #e8f3ff;
  color: #0052d9;
}

.ds-message.user .ds-msg-avatar {
  background: #0052d9;
  color: #fff;
}

.ds-message.user .ds-msg-inner {
  flex-direction: row-reverse;
}

.ds-message.user .ds-msg-body {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.ds-message.user .ds-msg-body {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.ds-msg-body {
  flex: 1;
  min-width: 0;
}

.ds-msg-text {
  font-size: 15px;
  line-height: 1.7;
  color: #333;
  word-break: break-word;
}

.ds-message.user .ds-msg-text {
  background: #f4f6fb;
  padding: 10px 16px;
  border-radius: 12px;
  display: inline-block;
  max-width: 85%;
}

/* Markdown 渲染 */
.ds-msg-text :deep(h1) { font-size: 1.3em; margin: 12px 0 6px; }
.ds-msg-text :deep(h2) { font-size: 1.15em; margin: 10px 0 6px; }
.ds-msg-text :deep(h3) { font-size: 1.05em; margin: 8px 0 4px; }
.ds-msg-text :deep(p) { margin: 4px 0; }
.ds-msg-text :deep(ul), .ds-msg-text :deep(ol) { padding-left: 20px; margin: 4px 0; }
.ds-msg-text :deep(code) {
  background: #f4f4f4;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
  font-family: ui-monospace, Consolas, monospace;
}
.ds-msg-text :deep(pre) {
  background: #f8f8f8;
  padding: 12px 16px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 8px 0;
}

/* Markdown 表格渲染 */
.ds-msg-text :deep(table) {
  display: block;
  border-collapse: collapse;
  width: 100%;
  max-width: 100%;
  overflow-x: auto;
  margin: 10px 0;
  font-size: 14px;
}
.ds-msg-text :deep(th) {
  background: #f0f5ff;
  border: 1px solid #d5dce9;
  padding: 8px 14px;
  text-align: left;
  font-weight: 600;
  color: #1a1a1a;
  white-space: nowrap;
}
.ds-msg-text :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 7px 14px;
  color: #333;
}
.ds-msg-text :deep(tr:nth-child(even)) td {
  background: #fafbfc;
}

/* 参考资料 */
.ds-sources {
  margin-top: 10px;
  padding: 12px;
  background: #f9fafb;
  border-radius: 8px;
  font-size: 13px;
  max-width: 500px;
}

.ds-sources-title {
  font-size: 13px;
  color: #888;
  margin-bottom: 6px;
}

.ds-source-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 13px;
}

.ds-source-score {
  color: #aaa;
  font-size: 12px;
}

/* 加载中 */
.ds-loading-hint {
  text-align: center;
  padding: 24px;
}

.ds-message-spacer {
  height: 8px;
}

/* ==================== 响应式 ==================== */
@media (min-width: 1024px) {
  .ds-sidebar {
    position: static;
    transform: none;
    width: 17%;
    min-width: 240px;
  }
  .ds-overlay { display: none; }
}

@media (max-width: 640px) {
  .ds-msg-inner { padding: 0 12px; }
  .ds-input-bar { padding: 12px 12px 8px; }
  .ds-disclaimer { display: none; }
}

/* ==================== 参考资料抽屉 ==================== */
.source-drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  min-width: 0;
}

.source-drawer-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}

.source-drawer-body {
  max-height: calc(100vh - 112px);
  overflow-y: auto;
  padding-right: 4px;
}

.source-drawer-meta {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0 12px;
  margin-bottom: 8px;
  background: #fff;
  color: #888;
  font-size: 12px;
}

.source-document-text {
  white-space: pre-wrap;
  word-break: break-word;
  color: #333;
  font-size: 14px;
  line-height: 1.8;
}

.source-hit {
  display: inline;
  padding: 2px 3px;
  border-radius: 3px;
  background: #fff1b8;
  color: inherit;
  box-shadow: 0 0 0 1px rgba(212, 136, 6, 0.22);
}

.source-empty {
  padding: 48px 0;
  color: #999;
  text-align: center;
  font-size: 14px;
}

/* 参考资料抽屉 — Markdown 渲染样式 */
.source-md :deep(h1) { font-size: 1.25em; margin: 16px 0 8px; padding-bottom: 6px; border-bottom: 1px solid #eee; }
.source-md :deep(h2) { font-size: 1.12em; margin: 14px 0 6px; }
.source-md :deep(h3) { font-size: 1.05em; margin: 10px 0 4px; }
.source-md :deep(p) { margin: 6px 0; line-height: 1.8; }
.source-md :deep(ul), .source-md :deep(ol) { padding-left: 22px; margin: 6px 0; }
.source-md :deep(li) { margin: 3px 0; line-height: 1.7; }
.source-md :deep(code) {
  background: #f4f4f4; padding: 2px 6px; border-radius: 4px;
  font-size: 13px; font-family: ui-monospace, Consolas, monospace;
}
.source-md :deep(pre) {
  background: #f8f8f8; padding: 12px 16px; border-radius: 8px; overflow-x: auto; margin: 8px 0;
}
.source-md :deep(pre code) { background: none; padding: 0; }
.source-md :deep(table) {
  border-collapse: collapse; width: 100%; margin: 10px 0; font-size: 14px;
}
.source-md :deep(th) {
  background: #f0f5ff; border: 1px solid #d5dce9; padding: 8px 14px;
  text-align: left; font-weight: 600; color: #1a1a1a; white-space: nowrap;
}
.source-md :deep(td) {
  border: 1px solid #e5e7eb; padding: 7px 14px; color: #333;
}
.source-md :deep(tr:nth-child(even)) td { background: #fafbfc; }
.source-md :deep(blockquote) {
  border-left: 3px solid #0052d9; padding: 8px 16px; margin: 10px 0;
  background: #f5f8ff; color: #555;
}
.source-md :deep(hr) { border: none; border-top: 1px solid #e5e7eb; margin: 16px 0; }
.source-md :deep(strong) { font-weight: 600; color: #1a1a1a; }
.source-md :deep(a) { color: #0052d9; }
</style>
