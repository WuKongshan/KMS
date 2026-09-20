/**
 * Client-side session state: the JWT and the cached user profile.
 *
 * 【中文】前端的会话状态：JWT 令牌与用户信息快照。
 * 用 localStorage 而不是内存变量，是为了刷新页面后仍保持登录态；
 * 代价是令牌可被同源脚本读取——这也是必须防 XSS 的原因。
 * 令牌由后端签发（kms-web），REST 与 WebSocket（kms-analyser）都复用它。
 *
 * 【中文】文件名刻意用 session 而不是 auth：src/api/auth.js 是"登录接口"，
 * 两者都叫 auth 时，同一目录下的相对导入极易指错文件（本项目就踩过一次），
 * 用不同的名字从源头消除歧义。
 */
const TOKEN_KEY = 'kms_token'
const PROFILE_KEY = 'kms_user'

// 读取令牌；不存在返回空串（调用方用真假判断即可）
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

// 登录成功后写入令牌
export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

// 读取用户信息快照（顶栏显示用）；解析失败时返回空对象而不是抛异常
export function getProfile() {
  try {
    return JSON.parse(localStorage.getItem(PROFILE_KEY) || '{}')
  } catch (e) {
    return {}
  }
}

// 保存用户信息快照（用户名/显示名/角色）
export function setProfile(profile) {
  localStorage.setItem(PROFILE_KEY, JSON.stringify(profile || {}))
}

// 清空会话：令牌失效、退出登录、检测到 401 时调用
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(PROFILE_KEY)
}
