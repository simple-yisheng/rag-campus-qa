import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

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

export async function ask(sessionId: string, question: string): Promise<AskResponse> {
  const res = await api.post('/chat/ask', { sessionId, question })
  return res.data.data
}

export async function getHistory(sessionId: string): Promise<any[]> {
  const res = await api.get(`/chat/history/${sessionId}`)
  return res.data.data || []
}
