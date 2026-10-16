<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getMetricDefs, updateMetricDef } from '@/api/metric'

// Metric threshold configuration (admin only). Before this page existed the only
// ways to change a threshold were editing seed code or writing to t_metric_def
// directly; this page closes that gap.
//
// 指标阈值配置页（仅 ADMIN）。此前改阈值只有两条路：改种子代码 或 直接写 t_metric_def，
// 这个页面把这个缺口补上：上下限、波动率阈值、动态阈值开关都能在界面上改，并写入操作审计。
const defs = ref([])
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref(null)
// 编辑中的表单：三个阈值 + 动态阈值开关
const form = ref({ upperThreshold: null, lowerThreshold: null, volatilityThreshold: null, dynamicThreshold: 0 })
// "该侧是否设阈值"的开关：取消勾选表示把这一侧置为 NULL（如剩余磁盘只关心下限）
// 与 form 分开存，是为了能区分"用户没启用"与"启用了但值为空"两种状态
const hasUpper = ref(true)
const hasLower = ref(true)
const hasVolatility = ref(true)

// 动态阈值：开关为 1 时上下限归分析器所有（每 5 分钟按 mean±2σ 重算），
// 界面上必须明确告知并禁止手工填写，否则用户会以为"改了没用"
const isDynamic = computed(() => form.value.dynamicThreshold === 1)

async function load() {
  loading.value = true
  try {
    defs.value = await getMetricDefs()
  } catch (e) {
    // 错误提示由 axios 拦截器统一弹出（403 会提示权限不足）
  } finally {
    loading.value = false
  }
}

onMounted(load)

// 打开编辑弹窗：把行数据摊平成表单，并用字段是否为 null 初始化"是否启用"开关
function openEdit(row) {
  editing.value = row
  form.value = {
    upperThreshold: row.upperThreshold,
    lowerThreshold: row.lowerThreshold,
    volatilityThreshold: row.volatilityThreshold,
    dynamicThreshold: row.dynamicThreshold === 1 ? 1 : 0
  }
  hasUpper.value = row.upperThreshold !== null && row.upperThreshold !== undefined
  hasLower.value = row.lowerThreshold !== null && row.lowerThreshold !== undefined
  hasVolatility.value = row.volatilityThreshold !== null && row.volatilityThreshold !== undefined
  // 未启用的字段给一个合理初值，勾选时不必从 0 开始调
  if (!hasUpper.value && form.value.upperThreshold === null) form.value.upperThreshold = 90
  if (!hasLower.value && form.value.lowerThreshold === null) form.value.lowerThreshold = 0
  if (!hasVolatility.value && form.value.volatilityThreshold === null) form.value.volatilityThreshold = 10
  dialogVisible.value = true
}

async function save() {
  // 前端只挡明显错误（真正裁决在后端，后端会返回具体原因）
  const upper = hasUpper.value ? form.value.upperThreshold : null
  const lower = hasLower.value ? form.value.lowerThreshold : null
  if (!isDynamic.value && upper === null && lower === null) {
    ElMessage.warning('上限与下限不能同时为空，否则该指标将不再产生阈值预警')
    return
  }
  if (upper !== null && lower !== null && upper <= lower) {
    ElMessage.warning('上限阈值必须大于下限阈值')
    return
  }
  saving.value = true
  try {
    // 始终提交全部四个字段：后端 PUT 是"整体替换"语义，漏传会被当成"清空"
    const updated = await updateMetricDef(editing.value.metricCode, {
      upperThreshold: upper,
      lowerThreshold: lower,
      volatilityThreshold: hasVolatility.value ? form.value.volatilityThreshold : null,
      dynamicThreshold: form.value.dynamicThreshold
    })
    // 用服务端落库后的真实值替换表格行（而不是把表单值回显），
    // 这样"动态指标的手工上下限被忽略"这类情况在页面上立刻可见
    const idx = defs.value.findIndex((d) => d.metricCode === updated.metricCode)
    if (idx >= 0) defs.value[idx] = updated
    dialogVisible.value = false
    ElMessage.success(`${updated.metricName || updated.metricCode} 的阈值配置已更新`)
    if (isDynamic.value) {
      ElMessage.info('该指标已启动态阈值：上下限将由系统每 5 分钟自动重算')
    }
  } catch (e) {
    // 拦截器已弹出后端返回的 400 原因
  } finally {
    saving.value = false
  }
}

// 数值展示：阈值可能为 NULL（例如剩余磁盘没有上限），空值统一显示为 ——
function show(v) {
  return v === null || v === undefined ? '—' : v
}
function formatTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}
</script>

<template>
  <div>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="阈值决定预警是否触发，修改会写入操作审计"
      description="上限：指标值高于它即越限；下限：低于它即越限（如剩余磁盘）。动态阈值开启后，上下限由系统每 5 分钟按最近 1 小时的 mean±2σ 自动重算，此时手工值会被忽略。"
      style="margin-bottom: 16px"
    />

    <div class="page-card">
      <div class="card-title">
        指标阈值配置
        <el-tag size="small" type="warning" effect="plain">仅 ADMIN 可修改</el-tag>
        <el-button size="small" style="margin-left: auto" :loading="loading" @click="load">刷新</el-button>
      </div>

      <el-table :data="defs" v-loading="loading" size="small" border>
        <el-table-column prop="metricCode" label="指标代码" width="120" />
        <el-table-column prop="metricName" label="指标名称" min-width="150" />
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column label="上限阈值" width="100" align="right">
          <template #default="{ row }">{{ show(row.upperThreshold) }}</template>
        </el-table-column>
        <el-table-column label="下限阈值" width="100" align="right">
          <template #default="{ row }">{{ show(row.lowerThreshold) }}</template>
        </el-table-column>
        <el-table-column label="波动率阈值" width="110" align="right">
          <template #default="{ row }">{{ show(row.volatilityThreshold) }} %</template>
        </el-table-column>
        <el-table-column label="阈值方式" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.dynamicThreshold === 1 ? 'success' : 'info'" size="small">
              {{ row.dynamicThreshold === 1 ? '动态重算' : '固定阈值' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后修改时间" width="170">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无指标定义" :image-size="60" />
        </template>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="`修改阈值配置 · ${editing?.metricName || ''}`" width="560px">
      <el-form label-width="110px">
        <el-form-item label="动态阈值">
          <el-switch v-model="form.dynamicThreshold" :active-value="1" :inactive-value="0" />
          <span class="hint" style="margin-left: 10px">
            {{ isDynamic ? '开启：上下限每 5 分钟按 mean±2σ 自动重算' : '关闭：使用下方固定阈值' }}
          </span>
        </el-form-item>

        <el-form-item label="上限阈值">
          <el-input-number v-model="form.upperThreshold" :disabled="!hasUpper || isDynamic" :step="1" />
          <el-checkbox v-model="hasUpper" :disabled="isDynamic" style="margin-left: 10px">启用</el-checkbox>
        </el-form-item>

        <el-form-item label="下限阈值">
          <el-input-number v-model="form.lowerThreshold" :disabled="!hasLower || isDynamic" :step="1" />
          <el-checkbox v-model="hasLower" :disabled="isDynamic" style="margin-left: 10px">启用</el-checkbox>
        </el-form-item>

        <el-form-item label="波动率阈值">
          <el-input-number v-model="form.volatilityThreshold" :disabled="!hasVolatility" :min="0" :step="1" />
          <el-checkbox v-model="hasVolatility" style="margin-left: 10px">启用</el-checkbox>
          <span class="hint" style="margin-left: 8px">相邻点波动超过该百分比即预警</span>
        </el-form-item>

        <!-- 【中文】动态阈值开启时必须明确提示：上下限的手工值会被服务端忽略，避免"改了没用"的困惑 -->
        <el-alert
          v-if="isDynamic"
          type="warning"
          :closable="false"
          show-icon
          title="该指标已启动态阈值"
          description="上下限由分析器每 5 分钟按最近 1 小时的 mean±2σ 自动重算，此处的手工上下限会被忽略；如需固定阈值，请先关闭动态阈值。波动率阈值不受影响，仍可修改。"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
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
.hint {
  font-size: 12px;
  color: #898781;
}
</style>
