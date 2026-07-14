import { createRouter, createWebHistory } from 'vue-router'
import LayoutView from '../views/LayoutView.vue'
import ChatView from '../views/ChatView.vue'
import DocumentView from '../views/DocumentView.vue'
import UserManageView from '../views/UserManageView.vue'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { guest: true }
    },
    {
      path: '/register',
      name: 'register',
      component: RegisterView,
      meta: { guest: true }
    },
    {
      path: '/',
      component: LayoutView,
      meta: { requiresAuth: true },
      children: [
        { path: '', name: 'chat', component: ChatView },
        { path: 'documents', name: 'documents', component: DocumentView },
        { path: 'users', name: 'users', component: UserManageView }
      ]
    }
  ]
})

// 路由守卫：未登录 → 登录页，已登录 → 不进登录/注册页
router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('token')
  if (to.meta.requiresAuth && !token) {
    next('/login')
  } else if (to.meta.guest && token) {
    next('/')
  } else {
    next()
  }
})

export default router
