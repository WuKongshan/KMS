import axios from 'axios'
import { ElMessage } from 'element-plus'

// All REST calls go through the web backend (same origin in production,
// Vite proxy in development). Interceptors unwrap the response body and
// surface errors as Element Plus messages (RuoYi style).
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '',
  timeout: 15000
})

request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const msg = error.response?.data?.message || error.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request
