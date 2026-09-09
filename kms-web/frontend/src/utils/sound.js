/**
 * Alert sound (spec 4.4, item 6): two-tone beep generated with the Web Audio
 * API so no audio asset is needed. First call may be blocked by the browser
 * autoplay policy until the user has interacted with the page; failures are
 * silent.
 */
let audioCtx = null

export function playAlertSound() {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext
    if (!Ctx) return
    audioCtx = audioCtx || new Ctx()
    if (audioCtx.state === 'suspended') {
      audioCtx.resume()
    }
    const t0 = audioCtx.currentTime
    // two descending tones: 880 Hz then 660 Hz, 180 ms apart
    ;[880, 660].forEach((freq, i) => {
      const osc = audioCtx.createOscillator()
      const gain = audioCtx.createGain()
      osc.type = 'sine'
      osc.frequency.value = freq
      const start = t0 + i * 0.18
      gain.gain.setValueAtTime(0.0001, start)
      gain.gain.exponentialRampToValueAtTime(0.25, start + 0.02)
      gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.15)
      osc.connect(gain)
      gain.connect(audioCtx.destination)
      osc.start(start)
      osc.stop(start + 0.16)
    })
  } catch (e) {
    // audio unavailable — the visual notification still carries the alert
  }
}
