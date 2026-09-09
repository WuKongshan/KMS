<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { getAlertPage, updateAlertStatus } from '@/api/alert'
import { formatValue } from '@/utils/metrics'

// Alert management (spec 4.4, item 4): all alert records from DM8 with
// level/status/time filters, mark-handled actions and auto-refresh.
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  level: null,
  status: null,
  range: null
})
const rows = ref([])
const total = ref(0)
const loading = ref(false)
let timer

const LEVEL_TAG = {
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
      level: query.level ?? undefined,
      status: query.status ?? undefined,
      begin: query.range?.[0],
      end: query.range?.[1]
    })
    rows.value = data.rows
    total.value = data.total
  } catch (e) {
    // interceptor shows the error
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNum = 1
  load()
}

function reset() {
  query.level = null
  query.status = null
  query.range = null
  search()
}

async function markHandled(row) {
  try {
    await updateAlertStatus(row.id, 1)
    ElMessage.success(`预警 #${row.id} 已标记为已处理`)
    load()
  } catch (e) {
    // interceptor shows the error
  }
}

async function markUnhandled(row) {
  try {
    await updateAlertStatus(row.id, 0)
    ElMessage.success(`预警 #${row.id} 已标记为未处理`)
    load()
  } catch (e) {
    // interceptor shows the error
  }
}

function fmtTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—'
}

onMounted(() => {
  load()
  timer = setInterval(load, 10000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div>
    <div class="page-card">
      <div class="filter-row">
        <span class="filter-label">预警级别</span>
        <el-select v-model="query.level" placeholder="全部" clearable style="width: 150px">
          <el-option label="1级 · 阈值超限" :value="1" />
          <el-option label="2级 · 波动异常" :value="2" />
          <el-option label="3级 · 严重预警" :value="3" />
        </el-select>
        <span class="filter-label">处理状态</span>
        <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
          <el-option label="未处理" :value="0" />
          <el-option label="已处理" :value="1" />
        </el-select>
        <span class="filter-label">时间范围</span>
        <el-date-picker
          v-model="query.range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :default-time="[new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]"
        />
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
            <el-tag :type="LEVEL_TAG[row.alertLevel]?.type || 'info'" size="small">
              {{ LEVEL_TAG[row.alertLevel]?.text || `${row.alertLevel}级` }}
            </el-tag>
          </template>
        </el-table-column>
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
