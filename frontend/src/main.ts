import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

import TDesign from 'tdesign-vue-next'
import TDesignChat from '@tdesign-vue-next/chat'
import 'tdesign-vue-next/es/style/index.css'
import '@tdesign-vue-next/chat/es/style/index.css'
import './style.css'

const app = createApp(App)
app.use(TDesign)
app.use(TDesignChat)
app.use(router)
app.mount('#app')
