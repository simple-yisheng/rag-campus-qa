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
