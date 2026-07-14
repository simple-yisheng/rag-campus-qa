import api from './index'

export interface SourceInfo {
  documentId: number
  title: string
  fileType?: string
  chunkIndex: number
  pageStart?: number
  pageEnd?: number
  score: number
  snippet: string
}

export interface AskResponse {
  sessionId: string
  answer: string
  sources: SourceInfo[]
}

export interface SessionInfo {
  sessionId: string
  title: string
  createTime: string
  lastTime: string
}

export async function getSessions(): Promise<SessionInfo[]> {
  const res = await api.get('/chat/sessions')
  return res.data.data || []
}

export async function ask(sessionId: string, question: string): Promise<AskResponse> {
  const res = await api.post('/chat/ask', { sessionId, question })
  return res.data.data
}

export async function getHistory(sessionId: string): Promise<any[]> {
  const res = await api.get(`/chat/history/${sessionId}`)
  return res.data.data || []
}

export async function deleteSession(sessionId: string): Promise<void> {
  await api.delete(`/chat/sessions/${sessionId}`)
}

export async function renameSession(sessionId: string, title: string): Promise<void> {
  await api.put(`/chat/sessions/${sessionId}`, { title })
}

// ==================== SSE 流式问答 ====================

export interface StreamCallbacks {
  onToken: (token: string) => void
  onSources: (sessionId: string, sources: SourceInfo[]) => void
  onDone: (fullAnswer: string) => void
  onError: (msg: string) => void
}

/** SSE 流式 RAG 问答 */
export async function askStream(
  sessionId: string,
  question: string,
  callbacks: StreamCallbacks
): Promise<void> {
  const token = localStorage.getItem('token')

  const response = await fetch('/api/chat/ask/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': token ? `Bearer ${token}` : ''
    },
    body: JSON.stringify({ sessionId: sessionId || '', question })
  })

  if (!response.ok) {
    callbacks.onError(`请求失败: ${response.status}`)
    return
  }

  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let currentEvent = ''
  let fullAnswer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        // 解析 event 行
        if (line.startsWith('event:')) {
          currentEvent = line.substring(6).trim()
          // 处理 "event: done" 在同一行的情况
          if (currentEvent === 'done') {
            callbacks.onDone(fullAnswer)
            return
          }
          continue
        }

        // 解析 data 行
        if (!line.startsWith('data:')) continue
        const raw = line.substring(5).trim()
        if (!raw) continue

        try {
          const data = JSON.parse(raw)

          if (currentEvent === 'sources') {
            callbacks.onSources(data.sessionId || '', data.sources || [])
          } else if (currentEvent === 'error') {
            callbacks.onError(data.error || '未知错误')
            return
          } else if (data.token) {
            fullAnswer += data.token
            callbacks.onToken(data.token)
          }
        } catch {
          // 非 JSON 数据行，忽略
        }
      }
    }

    // 流结束但没收到 done 事件
    callbacks.onDone(fullAnswer)

  } catch (e: any) {
    callbacks.onError(e.message || '网络错误')
  }
}
