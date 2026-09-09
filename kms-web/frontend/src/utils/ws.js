/**
 * WebSocket client singleton for the analyser's alert push endpoint
 * (ws://<host>:8082/ws/alerts, spec 4.3/4.4). Auto-reconnects every 5 s and
 * notifies subscribers about new alerts and connection state changes.
 * Override the URL with the VITE_WS_URL env var when the analyser runs on
 * another host.
 */
const WS_URL = import.meta.env.VITE_WS_URL || `ws://${window.location.hostname}:8082/ws/alerts`

let socket = null
let reconnectTimer = null
let connected = false
const alertHandlers = new Set()
const statusHandlers = new Set()

function emitStatus() {
  statusHandlers.forEach((handler) => handler(connected))
}

function scheduleReconnect() {
  if (reconnectTimer) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connect()
  }, 5000)
}

function connect() {
  try {
    socket = new WebSocket(WS_URL)
  } catch (e) {
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
      alertHandlers.forEach((handler) => handler(alert))
    } catch (e) {
      console.warn('Ignoring malformed WS payload:', event.data)
    }
  }
  socket.onclose = () => {
    connected = false
    emitStatus()
    scheduleReconnect()
  }
  socket.onerror = () => {
    // onclose always follows; no separate handling needed
  }
}

export default {
  connect,
  isConnected: () => connected,
  onAlert(handler) {
    alertHandlers.add(handler)
    return () => alertHandlers.delete(handler)
  },
  onStatus(handler) {
    statusHandlers.add(handler)
    return () => statusHandlers.delete(handler)
  }
}
