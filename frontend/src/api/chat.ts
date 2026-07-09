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
