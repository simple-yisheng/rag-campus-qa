<script setup lang="ts">
import { ref, nextTick, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { MessagePlugin } from 'tdesign-vue-next'
import { ask, getHistory, type AskResponse } from '../api/chat'
import { marked } from 'marked'

const router = useRouter()

// ========== 侧边栏 ==========
const sidebarOpen = ref(false)

// ========== 会话管理 ==========
interface Conversation {
  id: string
  title: string
}

const STORAGE_KEY = 'rag_conversations'

function loadConversations(): Conversation[] {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]') } catch { return [] }
}
function saveConversations(list: Conversation[]) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(list))
}

const conversations = ref<Conversation[]>(loadConversations())
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
  const id = generateUUID()
  const conv: Conversation = { id, title: '新对话' }
  conversations.value.unshift(conv)
  saveConversations(conversations.value)
  activeId.value = id
  messages.value = []
  sidebarOpen.value = false
}

function deleteConversation(id: string) {
  conversations.value = conversations.value.filter(c => c.id !== id)
  messageCache.delete(id)
  saveConversations(conversations.value)
  if (activeId.value === id) {
    activeId.value = ''
    messages.value = []
  }
}

// ========== 发送消息 ==========
async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  if (!activeId.value) {
    newConversation()
  }

  inputText.value = ''
  messages.value.push({ role: 'user', content: text })

  if (messages.value.filter(m => m.role === 'user').length === 1) {
    const conv = conversations.value.find(c => c.id === activeId.value)
    if (conv) {
      conv.title = text.length > 15 ? text.slice(0, 15) + '...' : text
      saveConversations(conversations.value)
    }
  }

  loading.value = true
  try {
    const res = await ask(activeId.value, text)
    messages.value.push({
      role: 'assistant',
      content: res.answer,
      sources: res.sources
    })
  } catch (e: any) {
    MessagePlugin.error(e?.response?.data?.errorMsg || '请求失败，请稍后重试')
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (messageContainer.value) {
      messageContainer.value.scrollTop = messageContainer.value.scrollHeight
    }
  })
}

// ========== 参考资料 ==========
function viewSource(doc: { documentId: number; title: string }) {
  window.open(`/api/documents/${doc.documentId}/file`)
}

// ========== 工具 ==========
function generateUUID(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
  })
}

// ========== Markdown 渲染（基于 marked，支持表格/代码块等） ==========
function renderMarkdown(text: string): string {
  if (!text) return ''
  return marked.parse(text, { breaks: true, gfm: true }) as string
}

onMounted(() => {
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
                    <span class="ds-source-score">匹配度 {{ (src.score * 100).toFixed(0) }}%</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 加载中 -->
          <div v-if="loading" class="ds-message assistant">
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
  border-collapse: collapse;
  width: 100%;
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
</style>
