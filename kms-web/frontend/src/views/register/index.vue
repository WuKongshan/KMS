<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { register } from '@/api/auth'
import { setProfile, setToken } from '@/utils/session'

// Registration page. Standalone route (no admin Layout), same visual language as
// the login page. On success the backend returns a JWT directly, so the user
// lands in the console without a second login round-trip.
//
// 注册页：独立路由（不挂后台 Layout），视觉与登录页保持一致。
// 注册成功后后端直接返回令牌，因此注册完即进入系统，不需要再登录一次。
const router = useRouter()
const loading = ref(false)
const form = ref({ username: '', password: '', confirm: '', nickname: '' })

// 与后端保持一致的前端预校验规则（仅用于即时提示；真正的裁决在后端）
const USERNAME_RE = /^[A-Za-z0-9_\u4e00-\u9fa5]{3,20}$/
const MIN_PASSWORD = 6

// 两次密码是否一致：只有第二个框有内容时才提示，避免刚输入就报红
const confirmMismatch = computed(
  () => !!form.value.confirm && form.value.confirm !== form.value.password
)

async function handleRegister() {
  const { username, password, confirm, nickname } = form.value
  // 前端校验只为减少一次无效往返，绝不替代后端校验
  if (!username || !password) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  if (!USERNAME_RE.test(username)) {
    ElMessage.warning('用户名需为 3~20 位字母、数字、下划线或中文')
    return
  }
  if (password.length < MIN_PASSWORD) {
    ElMessage.warning(`密码长度至少 ${MIN_PASSWORD} 位`)
    return
  }
  if (password !== confirm) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    // 不传 role：后端强制注册账号为 VIEWER（只读），传了也会被忽略
    const data = await register({ username, password, nickname: nickname || undefined })
    setToken(data.token)
    setProfile({ username: data.username, nickname: data.nickname, role: data.role })
    ElMessage.success('注册成功，已自动登录（角色：只读查看者）')
    router.replace('/')
  } catch (e) {
    // 错误提示由 axios 拦截器统一处理（用户名重复/密码过短等后端会返回具体原因）
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="register-page">
    <div class="register-card">
      <div class="register-head">
        <div class="register-title">注册账号</div>
        <div class="register-sub">关键指标监控系统</div>
      </div>

      <el-form label-position="top" @keyup.enter="handleRegister">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="3~20 位字母、数字、下划线或中文" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="至少 6 位"
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input
            v-model="form.confirm"
            type="password"
            show-password
            placeholder="再次输入密码"
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="昵称（可选）">
          <el-input v-model="form.nickname" placeholder="用于页面显示，留空则用用户名" />
        </el-form-item>

        <el-button type="primary" class="register-btn" :loading="loading" @click="handleRegister">
          注 册
        </el-button>
      </el-form>

      <!-- 【中文】角色说明放在最显眼处：新账号只有只读权限，避免注册后以为"没生效" -->
      <div class="register-tip">
        新账号为<b>只读查看者（VIEWER）</b>：可查看监控数据与预警，不能修改配置或查看日志。
        <br />
        <el-link type="primary" :underline="false" @click="router.push('/login')">已有账号？去登录</el-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.register-page {
  /* 【中文】与登录页一致：整屏居中卡片，浅色底 */
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f0f2f5;
  padding: 24px 0;
}
.register-card {
  width: 380px;
  padding: 28px 28px 20px;
  background: #fff;
  border-radius: 8px;
  border: 1px solid rgba(11, 11, 11, 0.1);
}
.register-head {
  text-align: center;
  margin-bottom: 16px;
}
.register-title {
  font-size: 18px;
  font-weight: 600;
}
.register-sub {
  font-size: 12px;
  color: #898781;
  margin-top: 4px;
}
.register-btn {
  width: 100%;
  margin-top: 4px;
}
.register-tip {
  margin-top: 16px;
  font-size: 12px;
  color: #898781;
  text-align: center;
  line-height: 1.8;
}
/* 【中文】紧凑化表单项间距：注册有 4 个字段，默认间距会让卡片过高 */
:deep(.el-form-item) {
  margin-bottom: 14px;
}
</style>
