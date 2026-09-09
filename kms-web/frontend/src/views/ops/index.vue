<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { disableCollector, enableCollector, getCollectorStatus, updatePeriod } from '@/api/ops'
import { formatValue } from '@/utils/metrics'

// Ops management (spec 4.4, item 1): collector status, last collection time,
// period form and enable/disable — all through the collector's REST API.
const status = ref(null)
const periodInput = ref(60)
const saving = ref(false)
const toggling = ref(false)
let timer

async function loadStatus() {
  try {
    const s = await getCollectorStatus()
    status.value = s
    if (s && s.periodSeconds) periodInput.value = s.periodSeconds
  } catch (e) {
    // request interceptor already shows the error message
  }
}

onMounted(() => {
  loadStatus()
  timer = setInterval(loadStatus, 5000)
})
onBeforeUnmount(() => clearInterval(timer))

async function savePeriod() {
  saving.value = true
  try {
    status.value = await updatePeriod(periodInput.value)
    ElMessage.success(`采集周期已更新为 ${status.value.periodSeconds} 秒`)
  } catch (e) {
    // interceptor shows the error
  } finally {
    saving.value = false
  }
}

async function toggleEnabled() {
  toggling.value = true
  try {
    status.value = status.value.enabled ? await disableCollector() : await enableCollector()
    ElMessage.success(status.value.enabled ? '采集已启用' : '采集已停用')
  } catch (e) {
    // interceptor shows the error
  } finally {
    toggling.value = false
  }
}

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
        <el-input-number v-model="periodInput" :min="60" :max="3600" :step="30" />
        <span class="hint">采集周期最小 60 秒，保存后无需重启采集器即生效</span>
        <el-button type="primary" :loading="saving" @click="savePeriod">保存</el-button>
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
        <el-col v-for="item in lastMetrics" :key="item.code" :xs="12" :sm="8" :md="4">
          <div class="stat-tile">
            <div class="stat-value">{{ formatValue(item.code, item.value) }}</div>
            <div class="stat-label">{{ item.code }}</div>
          </div>
        </el-col>
      </el-row>
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
  font-variant-numeric: tabular-nums;
}
.stat-label {
  font-size: 12px;
  color: #898781;
  margin-top: 4px;
}
</style>
