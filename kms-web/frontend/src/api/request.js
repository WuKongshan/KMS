import axios from 'axios'
import { ElMessage } from 'element-plus'

// All REST calls go through the web backend (same origin in production,
// Vite proxy in development). Interceptors unwrap the response body and
// surface errors as Element Plus messages (RuoYi style).
// 所有 REST 请求都经过 web 后端：生产环境页面与接口同源（Spring Boot 直接托管 dist 产物），
// 开发环境由 Vite 把 /api 代理到 8080 后端。拦截器统一解包响应体，
// 并把错误统一弹成 Element Plus 消息提示（若依风格），因此各页面不必再写 try/catch 弹窗。

// axios 单例：整个应用共用一份配置（baseURL、超时、拦截器）。
// baseURL 默认空字符串表示"同源"，可用 VITE_API_BASE 覆盖；
// timeout 15 秒，超时会走失败分支弹提示，避免表格/大屏一直转圈。
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '',
  timeout: 15000
})

// 响应拦截器：成功分支返回 response.data 做"解包"，失败分支统一弹错误提示。
request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    // 错误提示降级链：优先取响应体里的 message，其次 axios 自身的 message，最后兜底"请求失败"。
    // 注意：Spring Boot 3 的 server.error.include-message 默认为 never，所以第一级通常取不到
    // 后端写的中文提示，用户实际看到的多是 axios 的 "Request failed with status code 400"。
    const msg = error.response?.data?.message || error.message || '请求失败'
    ElMessage.error(msg)
    // 继续把错误抛给调用方：页面可选择静默忽略（保留旧数据）而不必再弹一次窗
    return Promise.reject(error)
  }
)

// 解包后调用方拿到的直接就是业务对象——后端直接返回 List/Map/实体，没有再包一层 { code, msg, data }
export default request
