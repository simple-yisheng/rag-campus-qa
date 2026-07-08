import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

export interface DocumentInfo {
  id: number
  title: string
  category: string
  department: string
  fileType: string
  status: string
  chunkCount: number
  createTime: string
}

export interface UploadResult {
  documentId: number
  title: string
  status: string
  message: string
}

export async function listDocuments(): Promise<DocumentInfo[]> {
  const res = await api.get('/documents')
  return res.data.data
}

export async function deleteDocument(id: number): Promise<void> {
  await api.delete(`/documents/${id}`)
}

export async function uploadDocument(
  file: File,
  category: string,
  title?: string,
  department?: string
): Promise<UploadResult> {
  const form = new FormData()
  form.append('file', file)
  form.append('category', category)
  if (title) form.append('title', title)
  if (department) form.append('department', department)
  const res = await api.post('/documents/upload', form)
  return res.data.data
}
