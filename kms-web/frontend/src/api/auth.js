import request from './request'

// Login endpoints. The token returned by /login is stored by utils/auth and
// injected into every later request by the axios interceptor.
// 登录相关接口：登录成功后拿到 JWT，由 utils/auth 存储，后续请求由 axios 拦截器自动带上。
export const login = (data) => request.post('/api/auth/login', data)
// 当前用户：页面刷新后用令牌换取身份信息（避免把角色等敏感信息长期放在本地）
export const fetchMe = () => request.get('/api/auth/me')
// 退出登录：无状态令牌下服务端不做事，仅为统一调用点；前端清掉令牌即完成退出
export const logout = () => request.post('/api/auth/logout')
