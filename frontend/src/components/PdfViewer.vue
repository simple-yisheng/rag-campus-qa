<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import * as pdfjsLib from 'pdfjs-dist'
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl

const props = defineProps<{
  fileUrl: string
  highlightText: string
  initialPage?: number
  pageEnd?: number
  renderRadius?: number
}>()

const container = ref<HTMLElement | null>(null)
const loading = ref(true)
const error = ref('')
const totalPages = ref(0)
const scale = ref(1.2)
const firstMatchPage = ref(-1)
const currentPage = ref(1)
const renderedStartPage = ref(1)
const renderedEndPage = ref(1)
const rendering = ref(false)

let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null

/** 清理搜索文本：去空白、取有效片段 */
function cleanSearchText(text: string): string {
  return text.replace(/\s+/g, '').replace(/\.{3,}$/, '')
}

/** 检查页面文本是否包含搜索词（宽松匹配） */
function pageContainsTerm(pageText: string, searchText: string): boolean {
  // 规范化：去空白后做子串匹配
  const normalized = pageText.replace(/\s+/g, '')
  const search = cleanSearchText(searchText)
  if (search.length < 4) return false

  // 尝试多个窗口大小
  const windows = [search, search.substring(0, 40), search.substring(0, 25)]
  for (const w of windows) {
    if (w.length >= 4 && normalized.includes(w)) return true
  }
  return false
}

async function loadPdf() {
  loading.value = true
  error.value = ''
  firstMatchPage.value = -1
  try {
    const loadingTask = pdfjsLib.getDocument(props.fileUrl)
    pdfDoc = await loadingTask.promise
    totalPages.value = pdfDoc.numPages

    const targetPage = normalizePage(props.initialPage)
    if (targetPage > 0) {
      firstMatchPage.value = targetPage
      currentPage.value = targetPage
    } else {
      // 兼容无后端页码的旧路径：扫描所有页面文字，找到匹配页。
      const searchText = cleanSearchText(props.highlightText)
      if (searchText.length >= 4 && pdfDoc) {
      for (let p = 1; p <= pdfDoc.numPages; p++) {
        const page = await pdfDoc.getPage(p)
        const textContent = await page.getTextContent()
        const pageText = textContent.items
          .map((item: any) => item.str)
          .join('')
        if (pageContainsTerm(pageText, props.highlightText)) {
          firstMatchPage.value = p
          break
        }
      }
      }
    }

    if (firstMatchPage.value > 0) {
      currentPage.value = firstMatchPage.value
    }

    // 渲染
    await renderPages()

    // 滚动到匹配页
    if (firstMatchPage.value > 0) {
      await nextTick()
      // 延迟一下等渲染完成
      setTimeout(() => scrollToPage(firstMatchPage.value), 200)
    }
  } catch (e: any) {
    error.value = 'PDF 加载失败：' + (e.message || '未知错误')
  } finally {
    loading.value = false
  }
}

function normalizePage(page?: number): number {
  if (!pdfDoc || !page) return -1
  return Math.min(Math.max(1, page), pdfDoc.numPages)
}

function getRenderRange(): { start: number; end: number } {
  if (!pdfDoc) return { start: 1, end: 1 }

  const shouldRenderAroundPage = props.initialPage || firstMatchPage.value > 0 || currentPage.value !== 1
  const targetPage = shouldRenderAroundPage ? normalizePage(currentPage.value || props.initialPage) : -1
  if (targetPage > 0) {
    const radius = props.renderRadius ?? 1
    const locatedEndPage = normalizePage(props.pageEnd)
    const rangeStartBase = locatedEndPage > 0 ? Math.min(targetPage, locatedEndPage) : targetPage
    const rangeEndBase = locatedEndPage > 0 ? Math.max(targetPage, locatedEndPage) : targetPage
    return {
      start: Math.max(1, rangeStartBase - radius),
      end: Math.min(pdfDoc.numPages, rangeEndBase + radius),
    }
  }

  return { start: 1, end: pdfDoc.numPages }
}

async function renderPages() {
  if (!pdfDoc || !container.value) return
  container.value.innerHTML = ''

  const dpr = window.devicePixelRatio || 1
  const range = getRenderRange()
  renderedStartPage.value = range.start
  renderedEndPage.value = range.end

  for (let pageNum = range.start; pageNum <= range.end; pageNum++) {
    const page = await pdfDoc.getPage(pageNum)
    const renderScale = scale.value * dpr
    const viewport = page.getViewport({ scale: renderScale })

    const pageDiv = document.createElement('div')
    pageDiv.className = 'pdf-page'
    pageDiv.dataset.page = String(pageNum)
    container.value.appendChild(pageDiv)

    const canvas = document.createElement('canvas')
    canvas.className = 'pdf-canvas'
    canvas.width = viewport.width
    canvas.height = viewport.height
    canvas.style.width = (viewport.width / dpr) + 'px'
    canvas.style.height = (viewport.height / dpr) + 'px'
    pageDiv.appendChild(canvas)

    const ctx = canvas.getContext('2d')!
    // 白底 — 在渲染前后各填一次，确保不被覆盖
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)

    await page.render({
      canvasContext: ctx,
      viewport,
      // @ts-ignore: PDF.js v4 支持 background 参数
      background: '#ffffff',
    }).promise

    // 如果渲染后 canvas 整体偏暗，再强制覆盖一层半透明白色
    const imageData = ctx.getImageData(0, 0, 1, 1)
    if (imageData.data[0] < 30 && imageData.data[1] < 30 && imageData.data[2] < 30) {
      ctx.fillStyle = '#ffffff'
      ctx.fillRect(0, 0, canvas.width, canvas.height)
      // 重新渲染到离屏 canvas，再画回来（兜底方案）
      const offscreen = document.createElement('canvas')
      offscreen.width = canvas.width
      offscreen.height = canvas.height
      const offCtx = offscreen.getContext('2d')!
      offCtx.fillStyle = '#ffffff'
      offCtx.fillRect(0, 0, offscreen.width, offscreen.height)
      await page.render({
        canvasContext: offCtx,
        viewport,
      }).promise
      ctx.drawImage(offscreen, 0, 0)
    }

  }
}

function scrollToPage(pageNum: number, behavior: ScrollBehavior = 'smooth') {
  const el = container.value?.querySelector(`[data-page="${pageNum}"]`)
  if (el) {
    el.scrollIntoView({ behavior, block: 'start' })
  }
}

async function goToPage(pageNum: number) {
  if (!pdfDoc) return
  const nextPage = Math.min(Math.max(1, pageNum), pdfDoc.numPages)
  currentPage.value = nextPage
  rendering.value = true
  await renderPages()
  await nextTick()
  scrollToPage(nextPage, 'auto')
  rendering.value = false
}

watch(() => [props.fileUrl, props.highlightText, props.initialPage, props.pageEnd], () => {
  if (pdfDoc) loadPdf()
})

onMounted(() => {
  if (props.fileUrl) loadPdf()
})

onUnmounted(() => {
  pdfDoc?.destroy()
})
</script>

<template>
  <div class="pdf-viewer-wrapper">
    <t-loading v-if="loading" text="正在加载 PDF..." size="small" style="padding:40px" />
    <div v-else-if="error" class="pdf-error">{{ error }}</div>

    <div v-show="!loading && !error">
      <div class="pdf-toolbar" v-if="totalPages > 0">
        <t-button variant="text" size="small" :disabled="currentPage <= 1"
          @click="goToPage(currentPage - 1)">◂</t-button>
        <span class="pdf-page-info">{{ currentPage }} / {{ totalPages }}</span>
        <t-button variant="text" size="small" :disabled="currentPage >= totalPages"
          @click="goToPage(currentPage + 1)">▸</t-button>
        <t-button variant="text" size="small"
          @click="scale = Math.max(0.6, scale - 0.1); loadPdf()">−</t-button>
        <span class="pdf-scale">{{ Math.round(scale * 100) }}%</span>
        <t-button variant="text" size="small"
          @click="scale = Math.min(3, scale + 0.1); loadPdf()">+</t-button>
        <t-button v-if="firstMatchPage > 0" variant="text" size="small" theme="primary"
          @click="goToPage(firstMatchPage)">定位参考</t-button>
      </div>
      <div class="pdf-stage" :class="{ rendering }">
        <t-loading v-if="rendering" text="正在渲染页面..." size="small" class="pdf-rendering" />
        <div ref="container" class="pdf-container" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.pdf-viewer-wrapper { min-height: 200px; }
.pdf-error { padding: 40px; text-align: center; color: #e34d59; font-size: 14px; }
.pdf-toolbar {
  display: flex; align-items: center; justify-content: center; gap: 4px;
  padding: 8px 0 12px; border-bottom: 1px solid #f0f0f0; margin-bottom: 12px;
  position: sticky; top: 0; background: #fff; z-index: 10;
}
.pdf-page-info, .pdf-scale {
  font-size: 13px; color: #666; min-width: 48px; text-align: center;
}
.pdf-container {
  display: flex; flex-direction: column; align-items: center; gap: 12px;
}
.pdf-stage {
  position: relative;
  min-height: 240px;
}
.pdf-stage.rendering .pdf-container {
  opacity: 0;
}
.pdf-rendering {
  position: absolute;
  inset: 48px 0 auto;
  z-index: 20;
}
.pdf-page {
  position: relative;
  box-shadow: 0 1px 8px rgba(0,0,0,0.06); background: #fff;
}
.pdf-canvas { display: block; }
</style>
