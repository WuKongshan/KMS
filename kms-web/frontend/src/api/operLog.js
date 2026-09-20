import request from './request'

// Operation audit log: who did what, when, from where, and whether it worked.
// 操作审计日志：谁在什么时候、从哪、做了什么、结果如何。
// username 支持模糊匹配；operType / module 为精确匹配；begin/end 为 ISO 时间（可选）。
export const getOperLogs = (params) => request.get('/api/oper-logs', { params })
