<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { login, fetchMe } from '@/api/auth'
import { setProfile, setToken } from '@/utils/session'

// Login page (option A: hand-rolled auth). Full-screen centred card — it is a
// standalone route, NOT a child of the admin Layout, so no sidebar/header.
//
// 登录页（方案 A：自建认证）。整屏居中的卡片式表单——它是独立路由，
// 不挂在后台 Layout 之下，因此没有侧边栏与顶栏。
const router = useRouter()
const form = ref({ username: '', password: '' })
const loading = ref(false)

async function handleLogin() {
  // 前端只做"非空"这类最基础的校验，真正的密码校验在后端（前端校验永远不可信）
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  loading.value = true
  try {
    const data = await login({ username: form.value.username, password: form.value.password })
    setToken(data.token)
    setProfile({ username: data.username, nickname: data.nickname, role: data.role })
    // 换取一次当前用户信息：确认令牌真的可用（顺便兼容后端将来调整令牌内容）
    try {
      await fetchMe()
    } catch (e) {
      // 失败不影响登录：令牌已验证通过，这里只是补充信息
    }
    // 登录成功回到首页（实时监控）
    router.replace('/')
  } catch (e) {
    // 错误提示已由 axios 拦截器统一弹出（401 → "用户名或密码错误"）
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-head">
        <div class="login-title">关键指标监控系统</div>
        <div class="login-sub">Key Indicator Monitoring System</div>
      </div>

      <!-- @keyup.enter 让回车即可提交，登录页的高频操作不必点按钮 -->
      <el-form label-position="top" @keyup.enter="handleLogin">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <!-- show-password：可切换明文，避免输错后再重来；type=password 保证默认不回显 -->
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
            autocomplete="current-password"
          />
        </el-form-item>
        <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">
          登 录
        </el-button>
      </el-form>

      <!-- 【中文】默认账号提示：首次部署时方便进入系统，生产环境应在首次登录后立即修改密码 -->
      <div class="login-tip">默认账号 admin / admin123，首次登录后请及时修改</div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  /* 【中文】占满整个视口并居中卡片；背景用浅色渐变以区别于内容页的白底 */
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f0f2f5;
}
.login-card {
  width: 360px;
  padding: 28px 28px 20px;
  background: #fff;
  border-radius: 8px;
  border: 1px solid rgba(11, 11, 11, 0.1);
}
.login-head {
  text-align: center;
  margin-bottom: 20px;
}
.login-title {
  font-size: 18px;
  font-weight: 600;
}
.login-sub {
  font-size: 12px;
  color: #898781;
  margin-top: 4px;
}
.login-btn {
  width: 100%;
  margin-top: 4px;
}
.login-tip {
  margin-top: 16px;
  font-size: 12px;
  color: #898781;
  text-align: center;
}
</style>
