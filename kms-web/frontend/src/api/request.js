import axios from 'axios'
import { ElMessage } from 'element-plus'
import { clearAuth, getToken } from '@/utils/session'

// All REST calls go through the web backend (same origin in production,
// Vite proxy in development). Interceptors attach the JWT and unwrap the body.
// 所有 REST 请求都经过 web 后端：生产环境同源（Spring Boot 托管 dist），
// 开发环境由 Vite 代理。拦截器负责注入 JWT、解包响应体、统一处理错误与登录失效。
//
// 【中文】注意：这里不 import router（避免与 router → views → api 形成循环依赖），
// 因此登录失效时用 window.location.hash 跳转，效果等价且没有循环引用问题。

// axios 单例：整个应用共用一份配置（baseURL、超时、拦截器）。
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '',
  timeout: 15000
})

// 请求拦截器：给每个请求带上 Bearer 令牌（登录接口本身不带也无妨）
request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：成功分支返回 response.data 做"解包"，失败分支统一弹错误提示。
request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status
    // 401 = 未登录或令牌失效：清掉本地令牌并回登录页。
    // 不做这一步的话，令牌过期后页面会一直弹"请求失败"却无法自救。
    if (status === 401) {
      clearAuth()
      // 已经在登录页时不再跳转，避免刷新页面被反复重定向
      if (!window.location.hash.startsWith('#/login')) {
        window.location.hash = '#/login'
      }
    }
    // 错误提示降级链：响应体 message → axios message → 兜底文案；
    // 401 用更明确的文案，因为此时后端返回体通常不含自定义 message。
    const msg = status === 401
      ? '登录状态已失效，请重新登录'
      : error.response?.data?.message || error.message || '请求失败'
    ElMessage.error(msg)
    // 继续把错误抛给调用方：页面可选择静默忽略（保留旧数据）而不必再弹一次窗
    return Promise.reject(error)
  }
)

// 解包后调用方拿到的直接就是业务对象——后端直接返回 List/Map/实体，没有再包一层 { code, msg, data }
export default request
