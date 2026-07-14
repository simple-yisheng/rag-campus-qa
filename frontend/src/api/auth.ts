import api from './index'

export interface LoginParams {
  username: string
  password: string
}

export interface RegisterParams {
  username: string
  password: string
  confirmPassword: string
}

export interface LoginResult {
  token: string
  username: string
  role: string
}

export interface UserInfo {
  id: number
  username: string
  role: string
  status: string
}

/** 登录 */
export function login(params: LoginParams): Promise<LoginResult> {
  return api.post('/auth/login', params).then(res => res.data.data)
}

/** 注册 */
export function register(params: RegisterParams): Promise<LoginResult> {
  return api.post('/auth/register', params).then(res => res.data.data)
}

/** 获取当前用户信息 */
export function getMe(): Promise<UserInfo> {
  return api.get('/auth/me').then(res => res.data.data)
}

// ==================== 用户管理（管理员） ====================

export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
}

export function listUsers(page = 1, size = 10): Promise<PageResult<UserInfo>> {
  return api.get('/admin/users', { params: { page, size } }).then(res => res.data.data)
}

export function createUser(data: { username: string; password: string; role: string }): Promise<void> {
  return api.post('/admin/users', data)
}

export function updateUser(id: number, data: { username?: string; role?: string; status?: string }): Promise<void> {
  return api.put(`/admin/users/${id}`, data)
}

export function resetUserPassword(id: number, password: string): Promise<void> {
  return api.put(`/admin/users/${id}/password`, { password })
}

export function deleteUser(id: number): Promise<void> {
  return api.delete(`/admin/users/${id}`)
}
