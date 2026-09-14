<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { disableCollector, enableCollector, getCollectorStatus, updatePeriod } from '@/api/ops'
import { formatValue } from '@/utils/metrics'

// Ops management (spec 4.4, item 1): collector status, last collection time,
// period form and enable/disable — all through the collector's REST API.
//
// 运维管理页（规格 4.4 第 1 项）：展示采集器状态、上次采集时间、最近一次采集值，
// 并提供采集周期修改与采集启停；这些操作全部经 web 后端代理转发到采集器的 REST API。
const status = ref(null)
// 采集器状态对象：{ available, enabled, periodSeconds, lastCollectTime, collectCount, lastError, lastMetrics }
const periodInput = ref(60)
// 周期输入框的值（秒），默认 60，会被服务端真实值不断校正
const saving = ref(false)
// 保存周期按钮的 loading
const toggling = ref(false)
// 启停按钮的 loading
let timer
// 5 秒状态轮询定时器句柄，卸载时清理

async function loadStatus() {
  try {
    const s = await getCollectorStatus()
    status.value = s
    // 关键一行：让输入框始终反映服务端真实值，避免"页面显示 90 秒、输入框还是 60，
    // 用户点保存反而把采集周期改回 60"这类事故
    if (s && s.periodSeconds) periodInput.value = s.periodSeconds
  } catch (e) {
    // request interceptor already shows the error message
    // 请求拦截器已经弹过错误提示（例如采集器不可达），这里不再重复提示
  }
}

onMounted(() => {
  loadStatus()
  // 每 5 秒轮询一次采集器状态；副作用是用户手动改的数字最多 5 秒后
  // 会被服务端真实值覆盖，这是刻意为之，用来防止拿过期值提交
  timer = setInterval(loadStatus, 5000)
})
onBeforeUnmount(() => clearInterval(timer))
// 卸载时清理轮询定时器，防止离开页面后仍继续发请求

async function savePeriod() {
  saving.value = true
  try {
    // 后端保存成功后会回传最新的采集器状态，直接覆盖本地 status
    status.value = await updatePeriod(periodInput.value)
    ElMessage.success(`采集周期已更新为 ${status.value.periodSeconds} 秒`)
  } catch (e) {
    // interceptor shows the error
  } finally {
    // 无论成功失败都结束按钮 loading
    saving.value = false
  }
}

async function toggleEnabled() {
  toggling.value = true
  try {
    // 根据当前状态决定调 enable 还是 disable，并把返回的最新状态写回 status
    status.value = status.value.enabled ? await disableCollector() : await enableCollector()
    ElMessage.success(status.value.enabled ? '采集已启用' : '采集已停用')
  } catch (e) {
    // interceptor shows the error
  } finally {
    toggling.value = false
  }
}

// 把采集器返回的驼峰字段（cpuUsage / diskFreeGb / netSendKbps ...）转换成页面统一的
// { code, value } 结构，方便复用 formatValue(code, value) 做单位换算与格式化
const lastMetrics = computed(() => {
  const m = status.value?.lastMetrics
  if (!m) return []
  return [
    { code: 'cpu_usage', value: m.cpuUsage },
    { code: 'mem_usage', value: m.memUsage },
    { code: 'disk_free', value: m.diskFreeGb },
    { code: 'net_send', value: m.netSendKbps },
    { code: 'net_recv', value: m.netRecvKbps },
    { code: 'cpu_temp', value: m.cpuTemp }
  ]
})
</script>

<template>
  <div>
    <!-- 【中文】采集器不可达时置顶展示红色告警条：后端 CollectorProxyService 连不上采集器时
         会降级返回 available:false，并把失败原因放在 lastError 中 -->
    <el-alert
      v-if="status && !status.available"
      type="error"
      :closable="false"
      show-icon
      title="采集器不可达"
      :description="status.lastError"
      style="margin-bottom: 16px"
    />

    <div class="page-card">
      <div class="card-title">
        采集器状态
        <el-tag
          v-if="status"
          :type="status.available ? (status.enabled ? 'success' : 'warning') : 'danger'"
          size="small"
        >
          {{ !status.available ? '不可达' : status.enabled ? '运行中' : '已停用' }}
        </el-tag>
      </div>
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="采集周期">
          {{ status?.periodSeconds ?? '—' }} 秒
        </el-descriptions-item>
        <el-descriptions-item label="上次采集时间">
          {{ status?.lastCollectTime ? status.lastCollectTime.replace('T', ' ') : '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="累计采集次数">
          {{ status?.collectCount ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="上次错误">
          {{ status?.lastError || '无' }}
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="page-card">
      <div class="card-title">周期配置</div>
      <div class="period-row">
        <!-- 【中文】周期输入：min=60 对应"采集周期 ≥ 60 秒"的硬性约束，step=30 便于按半分钟调整 -->
        <el-input-number v-model="periodInput" :min="60" :max="3600" :step="30" />
        <span class="hint">采集周期最小 60 秒，保存后无需重启采集器即生效</span>
        <!-- 【中文】保存：写入 t_collector_config，采集循环下一轮即读到新周期（无需重启） -->
        <el-button type="primary" :loading="saving" @click="savePeriod">保存</el-button>
        <!-- 【中文】:disabled="!status?.available"：采集器不可达时禁止启停，避免发出无效请求 -->
        <el-button
          :type="status?.enabled ? 'danger' : 'success'"
          :loading="toggling"
          :disabled="!status?.available"
          @click="toggleEnabled"
        >
          {{ status?.enabled ? '停用采集' : '启用采集' }}
        </el-button>
      </div>
    </div>

    <div class="page-card">
      <div class="card-title">最近一次采集值</div>
      <el-row :gutter="12">
        <!-- 【中文】响应式磁贴：手机每行 2 个、平板 3 个、桌面 6 个；
             数据来自采集器状态里一起返回的 lastMetrics（见 lastMetrics 计算属性） -->
        <el-col v-for="item in lastMetrics" :key="item.code" :xs="12" :sm="8" :md="4">
          <div class="stat-tile">
            <div class="stat-value">{{ formatValue(item.code, item.value) }}</div>
            <!-- 【中文】此处标签显示的是指标代码（英文常量），与实时监控页显示中文名不同 -->
            <div class="stat-label">{{ item.code }}</div>
          </div>
        </el-col>
      </el-row>
      <!-- 【中文】采集器尚未产出数据时（例如刚启用/刚重启）展示空状态 -->
      <el-empty v-if="!lastMetrics.length" description="采集器尚未产生数据" :image-size="60" />
    </div>
  </div>
</template>

<style scoped>
.card-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.period-row {
  display: flex;
  align-items: center;
  gap: 12px;
  /* 【中文】窄屏时输入框/说明/按钮自动换行 */
  flex-wrap: wrap;
}
.hint {
  font-size: 12px;
  color: #898781;
}
.stat-tile {
  border: 1px solid rgba(11, 11, 11, 0.1);
  border-radius: 6px;
  padding: 12px;
  text-align: center;
  margin-bottom: 12px;
  background: #f9f9f7;
}
.stat-value {
  font-size: 18px;
  font-weight: 600;
  /* 【中文】等宽数字：数值刷新时宽度稳定，磁贴不会左右抖动 */
  font-variant-numeric: tabular-nums;
}
.stat-label {
  font-size: 12px;
  color: #898781;
  margin-top: 4px;
}
</style>
