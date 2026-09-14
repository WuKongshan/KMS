/**
 * Alert sound (spec 4.4, item 6): two-tone beep generated with the Web Audio
 * API so no audio asset is needed. First call may be blocked by the browser
 * autoplay policy until the user has interacted with the page; failures are
 * silent.
 *
 * 【中文】预警提示音（规格 4.4 第 6 项）：用 Web Audio API 实时合成双音提示，
 * 不引入任何音频文件——零资源、频率可调，而"系统预警"这种场景单调的哔声反而比音乐更合适。
 * 首次调用可能被浏览器自动播放策略拦截（AudioContext 必须由用户交互后才能发声），
 * 失败时静默处理，不影响视觉弹窗。
 */
// 复用同一个 AudioContext：它创建开销较大，浏览器对同时存在的实例数也有限制。
let audioCtx = null

export function playAlertSound() {
  try {
    // window.AudioContext || window.webkitAudioContext：兼容老版 Safari 的 webkit 前缀实现
    const Ctx = window.AudioContext || window.webkitAudioContext
    if (!Ctx) return
    // 懒创建：首次播放时才 new，避免页面一加载就占用音频资源
    audioCtx = audioCtx || new Ctx()
    // state === 'suspended' 说明上下文被自动播放策略挂起，必须 resume() 才能出声
    //（首次加载页面时创建的就是该状态：此时音效不响，但视觉弹窗正常）
    if (audioCtx.state === 'suspended') {
      audioCtx.resume()
    }
    // 以当前音频时钟为基准，后面所有时间点都相对它计算
    const t0 = audioCtx.currentTime
    // two descending tones: 880 Hz then 660 Hz, 180 ms apart
    // 两个下行音：先 880Hz 再 660Hz，相隔 180ms（总时长约 340ms，下行音更容易被听成"告警"）
    ;[880, 660].forEach((freq, i) => {
      const osc = audioCtx.createOscillator()
      const gain = audioCtx.createGain()
      osc.type = 'sine'
      osc.frequency.value = freq
      // 第 i 个音在第 i×180ms 处起振
      const start = t0 + i * 0.18
      // 增益包络：20ms 淡入到 0.25，再在 130ms 内指数衰减回极小值，
      // 避免波形垂直跳变产生"咔哒"爆音；指数曲线的目标值不能为 0（永远到不了），故用 0.0001 代替
      gain.gain.setValueAtTime(0.0001, start)
      gain.gain.exponentialRampToValueAtTime(0.25, start + 0.02)
      gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.15)
      // 音频链路：振荡器 → 增益节点 → 扬声器
      osc.connect(gain)
      gain.connect(audioCtx.destination)
      // 起振并在 160ms 后停止（略长于包络，等衰减走完再自然结束，振荡器可被回收）
      osc.start(start)
      osc.stop(start + 0.16)
    })
  } catch (e) {
    // audio unavailable — the visual notification still carries the alert
    // 空实现兜底：声音只是增强手段，视觉通知才是主要通道，
    // 音频不可用（无音频设备、被自动播放策略拦截、API 不存在）时不应打断后续的 ElNotification 弹窗。
  }
}
