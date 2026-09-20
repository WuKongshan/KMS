<script setup>
import { onMounted, ref } from 'vue'
import { getOperLogs } from '@/api/operLog'

// Operation audit log page: "who did what".
// 操作日志页：回答"谁做了什么"——时间、用户、模块、操作、参数、结果、来源 IP、耗时。
// 与"系统日志"页的区别：那一页看的是技术日志（线程/SQL/异常栈），用于排障；
// 这一页看的是人的行为，用于追溯与合规。
const loading = ref(false)
// 表格数据与分页
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)

// 筛选条件：全部可选，留空即不参与查询
const filters = ref({
  username: '',
  operType: '',
  range: null // el-date-picker 的 [开始, 结束]，格式 YYYY-MM-DDTHH:mm:ss
})

// 操作类型选项：与后端写入的 operType 枚举一一对应
const OPER_TYPES = [
  { value: 'LOGIN', label: '登录' },
  { value: 'LOGOUT', label: '退出' },
  { value: 'PERIOD', label: '修改采集周期' },
  { value: 'ENABLE', label: '启用采集' },
  { value: 'DISABLE', label: '停用采集' },
  { value: 'ALERT_STATUS', label: '预警处理' },
  { value: 'VIEW_LOG', label: '查看日志' }
]

// 类型 → 中文标签（未知类型兜底显示原始枚举值，便于后端新增类型时不至于空白）
const typeLabel = (value) => OPER_TYPES.find((t) => t.value === value)?.label || value || '-'

// 模块 → 标签色：不同模块用不同颜色，一眼区分"谁在哪个功能上动了手"
const MODULE_TAG = {
  认证: 'info',
  运维: 'warning',
  预警: 'danger',
  日志: ''
}
const moduleTag = (module) => MODULE_TAG[module] ?? 'info'

// 加载一页审计日志
async function load() {
  loading.value = true
  try {
    const params = {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      username: filters.value.username || undefined,
      operType: filters.value.operType || undefined,
      // el-date-picker 的 value-format 已产出 ISO 字符串，后端按 ISO 解析
      begin: filters.value.range?.[0] || undefined,
      end: filters.value.range?.[1] || undefined
    }
    const data = await getOperLogs(params)
    rows.value = data.rows || []
    total.value = data.total || 0
  } catch (e) {
    // 错误提示由 request 拦截器统一处理，这里保留上一屏数据
  } finally {
    loading.value = false
  }
}

// 查询：条件变化后必须回到第 1 页，否则会停在越界的页码上看到空列表
function search() {
  pageNum.value = 1
  load()
}

// 重置筛选条件并重新查询
function reset() {
  filters.value = { username: '', operType: '', range: null }
  search()
}

// 分页变化（页码或每页条数）后重新查询
function handlePageChange(page) {
  pageNum.value = page
  load()
}
function handleSizeChange(size) {
  pageSize.value = size
  pageNum.value = 1
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-card">
      <div class="filter-row">
        <span class="filter-label">用户</span>
        <el-input
          v-model="filters.username"
          style="width: 160px"
          placeholder="用户名（支持模糊）"
          clearable
          @keyup.enter="search"
        />

        <span class="filter-label">操作类型</span>
        <el-select v-model="filters.operType" style="width: 170px" clearable placeholder="全部">
          <el-option v-for="t in OPER_TYPES" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>

        <span class="filter-label">时间范围</span>
        <!-- 【中文】value-format 与后端 @DateTimeFormat(iso = ISO.DATE_TIME) 对齐 -->
        <el-date-picker
          v-model="filters.range"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          style="width: 360px"
        />

        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </div>

    <div class="page-card">
      <div class="card-title">
        <span>操作记录</span>
        <span class="hint">共 {{ total }} 条 · 按时间倒序</span>
      </div>

      <!-- 【中文】加载中不整表遮罩，保留上一屏数据可读，体验更平滑 -->
      <el-table :data="rows" border stripe size="small" style="width: 100%">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ (row.createTime || '').replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column prop="username" label="用户" width="110" />
        <el-table-column label="模块" width="90">
          <!-- 【中文】模块用彩色标签区分，扫一眼就知道操作集中在哪个功能上 -->
          <template #default="{ row }">
            <el-tag :type="moduleTag(row.module)" size="small" effect="plain">{{ row.module }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">{{ typeLabel(row.operType) }}</template>
        </el-table-column>
        <el-table-column prop="operDesc" label="操作详情" min-width="220" show-overflow-tooltip />
        <el-table-column label="参数" min-width="180">
          <!-- 【中文】参数里的 password/token 已由后端打码为 ***，此处原样展示 -->
          <template #default="{ row }">
            <span class="mono">{{ row.requestParams || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="110">
          <template #default="{ row }">
            <!-- 【中文】失败时把原因放进 tooltip，避免把长错误信息摊在表格里 -->
            <el-tooltip v-if="row.status === 1" :content="row.errorMsg || '失败'" placement="top">
              <el-tag type="danger" size="small">失败</el-tag>
            </el-tooltip>
            <el-tag v-else type="success" size="small">成功</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="来源 IP" width="130" />
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ row.costMs }} ms</template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && !rows.length" description="没有匹配的操作记录" :image-size="60" />

      <div class="pager">
        <el-pagination
          :current-page="pageNum"
          :page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
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
  /* 【中文】窄屏自动换行，避免筛选项溢出 */
  flex-wrap: wrap;
}
.filter-label {
  font-size: 13px;
  color: #5f5e5a;
}
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
.mono {
  /* 【中文】参数用等宽字体，JSON 片段更易读 */
  font-family: ui-monospace, Consolas, 'Courier New', monospace;
  font-size: 12px;
  color: #52514e;
}
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
