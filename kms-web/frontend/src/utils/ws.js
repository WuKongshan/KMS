/**
 * WebSocket client singleton for the analyser's alert push endpoint
 * (ws://<host>:8082/ws/alerts, spec 4.3/4.4). Auto-reconnects every 5 s and
 * notifies subscribers about new alerts and connection state changes.
 * Override the URL with the VITE_WS_URL env var when the analyser runs on
 * another host.
 *
 * 【中文】分析器预警推送的 WebSocket 客户端单例（ws://<主机>:8082/ws/alerts，规格 4.3/4.4）：
 * 断线每 5 秒自动重连，并向订阅者广播"新预警"与"连接状态变化"两类事件。
 * 分析器部署在别的机器时用 VITE_WS_URL 环境变量覆盖地址。
 */
// 用 window.location.hostname 而不是硬编码 localhost：别人通过局域网 IP 打开页面时也能连上分析器；
// 浏览器直连 8082（不经 Vite/Spring 代理），所以生产部署必须保证 8082 对浏览器可达。
const WS_URL = import.meta.env.VITE_WS_URL || `ws://${window.location.hostname}:8082/ws/alerts`

// 模块级单例：ES Module 只会被求值一次，因此下面的变量天然全局唯一，
// 多个组件 import 本模块时共享同一条 WS 连接，不会各建一条。
let socket = null
let reconnectTimer = null
let connected = false
// 用 Set 保存订阅者：支持多个组件同时订阅，重复订阅自动去重，退订只需 delete。
const alertHandlers = new Set()
const statusHandlers = new Set()

// 把最新的连接状态广播给所有状态订阅者（侧边栏/大屏的"已连接"标识靠它刷新）。
function emitStatus() {
  statusHandlers.forEach((handler) => handler(connected))
}

// 5 秒后重连。开头的 if (reconnectTimer) return 用于防止 onclose 连续触发时
// 调度出多个重连定时器——多个定时器会让连接数成倍增长。
function scheduleReconnect() {
  if (reconnectTimer) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connect()
  }, 5000)
}

// 建立连接并挂上四个事件回调。
// 幂等：layout 与 AlertPopup 都会调用 connect()（大屏页面同理），若不做判断就会各建一条连接，
// 服务端广播时两条连接都收到消息，同一条预警会被反复弹窗。因此先检查 readyState：
// OPEN/CONNECTING 表示已有可用连接，直接复用；只有 CLOSED/CLOSING 或尚未建连时才新建。
function connect() {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return
  }
  try {
    socket = new WebSocket(WS_URL)
  } catch (e) {
    // 构造函数抛错（如 URL 非法）时也要兜底重连，否则连接会永久断开
    scheduleReconnect()
    return
  }
  socket.onopen = () => {
    connected = true
    emitStatus()
  }
  socket.onmessage = (event) => {
    try {
      const alert = JSON.parse(event.data)
      // 一条预警广播给所有订阅者（大屏弹窗与预警列表可能同时订阅）
      alertHandlers.forEach((handler) => handler(alert))
    } catch (e) {
      // 单条报文解析失败不影响整条连接，只告警并丢弃
      console.warn('Ignoring malformed WS payload:', event.data)
    }
  }
  socket.onclose = () => {
    connected = false
    emitStatus()
    // 只在 onclose 里触发重连：onerror 之后必然会再走 onclose，
    // 两处都重连会导致重复重连、连接翻倍。
    scheduleReconnect()
  }
  socket.onerror = () => {
    // onclose always follows; no separate handling needed
    // onerror 之后必然紧跟 onclose，因此这里空实现，重连统一交给 onclose 处理。
  }
}

// 对外暴露单例：connect 建连，isConnected 查状态，onAlert/onStatus 用于订阅。
// onAlert/onStatus 返回的是"退订函数"，务必在组件的 onBeforeUnmount 里调用，
// 否则路由来回切换会不断累积回调，出现"一条预警弹出 N 个窗"的问题。
export default {
  connect,
  isConnected: () => connected,
  onAlert(handler) {
    alertHandlers.add(handler)
    // 返回退订闭包，配合组件 onBeforeUnmount 使用
    return () => alertHandlers.delete(handler)
  },
  onStatus(handler) {
    statusHandlers.add(handler)
    // 同样返回退订闭包，防止状态回调堆积
    return () => statusHandlers.delete(handler)
  }
}
