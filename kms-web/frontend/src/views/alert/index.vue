<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { getAlertPage, updateAlertStatus } from '@/api/alert'
import { formatValue } from '@/utils/metrics'

// Alert management (spec 4.4, item 4): all alert records from DM8 with
// level/status/time filters, mark-handled actions and auto-refresh.
//
// 预警管理页（规格 4.4 第 4 项）：分页展示达梦（DM8）中的全部预警记录，
// 支持按级别 / 处理状态 / 时间范围筛选，可标记处理或未处理，并定时自动刷新。
const query = reactive({
  // 筛选条件对象：分页信息 + 级别 + 处理状态 + 时间范围，null 表示"未筛选"
  pageNum: 1,
  pageSize: 10,
  level: null,
  status: null,
  range: null
})
const rows = ref([])
// 当前页数据
const total = ref(0)
// 总记录数，由后端返回并交给 el-pagination 计算页数
const loading = ref(false)
// 表格 loading 状态
let timer
// 10 秒自动刷新定时器句柄，卸载时清理

const LEVEL_TAG = {
  // 预警级别 → 标签样式/文案映射：1 级 warning、2 级 primary、3 级 danger
  1: { type: 'warning', text: '1级 · 阈值超限' },
  2: { type: 'primary', text: '2级 · 波动异常' },
  3: { type: 'danger', text: '3级 · 严重预警' }
}

async function load() {
  loading.value = true
  try {
    const data = await getAlertPage({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      // query.level ?? undefined：null 会被 axios 序列化成空串（level=），
      // 用 ?? undefined 让 axios 直接忽略该字段，后端收到的参数里完全不出现 level
      level: query.level ?? undefined,
      // 同上：status 未选择时不下发该参数
      status: query.status ?? undefined,
      // 可选链 + 空值合并：筛选器未选时整体为 undefined，
      // 把"筛选器为空"的语义干净地传递到后端（而不是传空串）
      begin: query.range?.[0],
      end: query.range?.[1]
    })
    // 若依风格分页返回 { total, rows }：total 交给分页组件，rows 交给表格
    rows.value = data.rows
    total.value = data.total
  } catch (e) {
    // interceptor shows the error
  } finally {
    loading.value = false
  }
}

function search() {
  // 筛选条件变化后回到第 1 页再查，避免停留在越界页码上查出空列表
  query.pageNum = 1
  load()
}

function reset() {
  // 清空全部筛选条件（统一置回 null）后重新查询
  query.level = null
  query.status = null
  query.range = null
  search()
}

async function markHandled(row) {
  try {
    // 状态 1 = 已处理
    await updateAlertStatus(row.id, 1)
    ElMessage.success(`预警 #${row.id} 已标记为已处理`)
    // 调完接口立即刷新列表，不等 10 秒定时器，让用户马上看到状态变化
    load()
  } catch (e) {
    // interceptor shows the error
  }
}

async function markUnhandled(row) {
  try {
    // 状态 0 = 未处理
    await updateAlertStatus(row.id, 0)
    ElMessage.success(`预警 #${row.id} 已标记为未处理`)
    // 同样立即刷新
    load()
  } catch (e) {
    // interceptor shows the error
  }
}

function fmtTime(value) {
  // 时间格式化：空值显示破折号，避免渲染出 Invalid Date
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—'
}

onMounted(() => {
  load()
  // 10 秒自动刷新一次：比实时监控页的 5 秒慢，
  // 因为预警产生频率较低，而且刷新会打断用户翻页/查看
  timer = setInterval(load, 10000)
})
onBeforeUnmount(() => clearInterval(timer))
// 卸载时清理定时器，避免离开页面后仍在持续请求
</script>

<template>
  <div>
    <div class="page-card">
      <div class="filter-row">
        <span class="filter-label">预警级别</span>
        <!-- 【中文】clearable 清空后 query.level 变回 null，load() 里用 ?? undefined 让该参数不下发 -->
        <el-select v-model="query.level" placeholder="全部" clearable style="width: 150px">
          <el-option label="1级 · 阈值超限" :value="1" />
          <el-option label="2级 · 波动异常" :value="2" />
          <el-option label="3级 · 严重预警" :value="3" />
        </el-select>
        <span class="filter-label">处理状态</span>
        <!-- 【中文】状态取值 0=未处理 / 1=已处理，与后端 t_alert_record.status 一致 -->
        <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
          <el-option label="未处理" :value="0" />
          <el-option label="已处理" :value="1" />
        </el-select>
        <span class="filter-label">时间范围</span>
        <!-- 【中文】时间范围：未选时 query.range 为 null，
             load() 里用 query.range?.[0] 可选链取起点/终点，未选即 undefined（不传该参数） -->
        <el-date-picker
          v-model="query.range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :default-time="[new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]"
        />
        <!-- 【中文】查询会先回到第 1 页（见 search），重置会清空全部条件 -->
        <el-button type="primary" @click="search">
          <el-icon><Search /></el-icon>&nbsp;查询
        </el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </div>

    <div class="page-card">
      <el-table v-loading="loading" :data="rows" size="small" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="时间" min-width="150">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column prop="metricName" label="指标" min-width="120" />
        <el-table-column label="级别" width="130">
          <template #default="{ row }">
            <!-- 【中文】级别标签查 LEVEL_TAG 映射；未知级别兜底为灰色 info + "N级" -->
            <el-tag :type="LEVEL_TAG[row.alertLevel]?.type || 'info'" size="small">
              {{ LEVEL_TAG[row.alertLevel]?.text || `${row.alertLevel}级` }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- 【中文】"类型"列直接展示后端英文常量（THRESHOLD / VOLATILITY / THRESHOLD_VOLATILITY），不做中文映射 -->
        <el-table-column prop="alertType" label="类型" width="160" />
        <el-table-column label="当前值" width="110" align="right">
          <template #default="{ row }">
            {{ formatValue(row.metricCode, row.currentValue) }}
          </template>
        </el-table-column>
        <el-table-column label="阈值" width="110" align="right">
          <template #default="{ row }">
            {{ formatValue(row.metricCode, row.thresholdValue) }}
          </template>
        </el-table-column>
        <el-table-column label="波动率" width="90" align="right">
          <template #default="{ row }">
            {{ row.volatility == null ? '—' : `${Number(row.volatility).toFixed(2)}%` }}
          </template>
        </el-table-column>
        <el-table-column prop="message" label="消息" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '已处理' : '未处理' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status !== 1"
              link
              type="primary"
              size="small"
              @click="markHandled(row)"
            >
              标记处理
            </el-button>
            <el-button v-else link type="warning" size="small" @click="markUnhandled(row)">
              标记未处理
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 【中文】若依风格分页：:total 来自后端返回的 total；翻页直接 load()，
           改变每页条数则走 search()（先回到第 1 页再查询） -->
      <div class="pagination-row">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="load"
          @size-change="search"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.filter-label {
  font-size: 13px;
  color: #52514e;
}
.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
