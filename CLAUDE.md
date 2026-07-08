# CLAUDE.md

## 项目概况

校园智答 — 基于 RAG 的校园知识库问答系统。Spring Boot 3.2 + MyBatis-Plus + Redis + RabbitMQ + DeepSeek + DashScope。

## 环境

```bash
# 启动 Docker 中间件
docker compose up -d        # MySQL 8.0:3306, Redis 7:6379, RabbitMQ 3.12:5672/15672

# 数据库自动初始化（init.sql 挂载到容器），三张表：
#   tb_document, tb_document_chunk, tb_conversation

# API Key 配置在 application-local.yaml（gitignored，不上传）
# 真实 Key 不在此文件中，需用户自行配置
```

## 核心架构

```
上传:  MD5去重 → DocumentConverter(策略模式) → MySQL(PENDING) + MinIO(原始文件+PDF预览) → RabbitMQ → 异步处理
异步:  DocumentChunker(4级自适应,表格感知) → 页码推断 → EmbeddingClient(≤10分批) → MySQL + VectorStore(内存)
问答:  查询改写(多轮上下文) → Embedding → VectorStore.search(宽窗口) → 关键词加权 → 文档标题匹配 → 文档多样性截断 → DeepSeek生成 → MySQL+Redis持久化(瘦身Q&A)
```

### MinIO 对象存储

- 原始文件存储: `MinioStorageService` — 上传时自动存入 `documents/{docId}/{filename}`
- Word→PDF 预览: `OfficePreviewService` — 调用 LibreOffice headless 将 DOCX/DOC 转 PDF 存入 MinIO（previewFileKey），供前端 PDF.js 统一渲染
- 下载接口: `GET /api/documents/{id}/file` — PDF 内联预览，其他格式触发下载（【已知问题】使用自增 ID 可被遍历访问，待改为随机 token）
- 预览接口: `GET /api/documents/{id}/preview` — PDF 返回原始文件，Word 返回 LibreOffice 转换的 PDF（无预览时返回提示）
- 管理控制台: `http://localhost:9001`（minioadmin/minioadmin）
- MinIO 故障不影响检索流程（upload 中 try-catch 容错）
- Docker 数据位置: `D:\Docker\DockerDesktopWSL\`

## 分块策略（4级自适应优先级）

1. Markdown 标题切分 — 检测 `#`/`##`/`###` → chunkByMarkdown()
2. Q&A 格式切分 — 检测 `Q1`/`Q2` 等 → chunkByQA()（自动跳过目录页）
3. 中文结构切分 — POLICY/SCHOLARSHIP/ACADEMIC + "第X章" → chunkByStructure()（过滤总结句）
4. 滑动窗口兜底 — 800字/块, 200字重叠，Markdown 表格感知：跨 chunk 自动补表头+分隔线

### 页码推断

- PDF 分块时按 chunk 文本反查 pdfbox 页面文字，确定每个 chunk 的 pageStart/pageEnd
- 存入 tb_document_chunk.page_start / page_end，经 SourceInfo 返回前端
- 前端 PdfViewer 通过 initialPage 属性直接跳转到参考页面

## 关键实现细节

- **EmbeddingClient**: DashScope 兼容模式 API，单次最多10条，超限自动分批 + 200ms 延迟
- **VectorStore**: ConcurrentHashMap 内存索引，启动时 @PostConstruct 从 DB 加载，余弦相似度遍历
- **检索增强**: 宽窗口召回(topK×3) → 关键词加权(向量分 + 0.2×命中率) → 文档标题实体匹配加分(如查询含"材料学院"→材料学院文档chunk+0.15) → 文档多样性截断(每文档≤3 chunk) → 截断 topK=12
- **DocumentConverter**: 策略模式，PdfBoxConverter(.pdf，坐标分析表格检测→Markdown表格输出，不覆盖writeString保留下标处理) + PlainTextConverter(.txt/.md) + DocxConverter(.docx/.doc，表格→Markdown表格)，预留 MarkItDownConverter
- **OfficePreviewService**: Word文档上传时调用 LibreOffice headless 转 PDF，存入 MinIO 供前端 PDF.js 统一渲染
- **DocxConverter**: 遍历 XWPFDocument.bodyElements，段落保留原文，表格格式化为 Markdown 表格（管道符+分隔线），避免表格结构丢失导致分块截断
- **MD5 去重**: 上传时计算文件字节 MD5，查询 content_hash 列，已存在且 status=DONE 则拦截返回
- **MinIO 存储**: 上传时自动存入原始文件，`GET /api/documents/{id}/file` 下载预览，MinIO 故障不影响检索
- **SourceInfo 增强**: 返回 documentId，前端可据此请求原始文件实现"点击参考资料查看原文"
- **文档缓存**: RagServiceImpl 请求级 HashMap，避免 buildUserPrompt + buildSources 重复查 DB（16次→1次）
- **content 字段**: 处理完成后清空，原文从 tb_document_chunk 拼接还原，避免大字段拖慢列表查询
- **多轮对话**: Redis `rag:conversation:{sessionId}` 存储瘦身 Q&A（仅原始问答，去 system prompt 和 chunk），24h TTL；查询改写：追问先结合上文经 LLM 改写为独立完整查询，再向量化检索
- **参考资料定位**: GET /api/documents/{id}/content 从 chunks 拼接全文；前端点击参考资料→侧边抽屉(t-drawer)展示，PDF 用 PdfViewer(PDF.js)渲染+页码定位+文字高亮，Word 用 LibreOffice 转 PDF 预览，无预览时降级为纯文本模式+chunk高亮
- **PdfViewer 组件**: 基于 pdfjs-dist v4，支持 DPR 高清渲染、页码定位(initialPage/pageEnd)、范围渲染(renderRadius)、缩放、文字层高亮匹配
- **桥接方法**: getDocumentById(Long) 解决 MyBatis-Plus selectById(Serializable) 无法匹配 Function<Long, Document> 的问题

## 安全

- `application.yaml` → 占位符默认值，可提交
- `application-local.yaml` → 真实 API Key，.gitignore 已排除
- `spring.profiles.active: local` 激活本地配置

## RAG 参数

```yaml
rag.chunk.size: 800
rag.chunk.overlap: 200
rag.retrieval.top-k: 8
rag.conversation.max-history: 10
rag.conversation.ttl-hours: 24
```

## 已完成

| 日期 | 任务 |
|------|------|
| 2026-07-08 | 多轮对话上下文能力 — ① 历史记录瘦身：Redis 只存原始 Q&A，去 system prompt、去 chunk；② 查询改写：追问先结合上文 LLM 改写为独立查询再检索 |
| 2026-07-08 | 表格分块修复 — ① chunkBySlidingWindow 表格感知：跨 chunk 自动补表头+分隔线；② PdfBoxConverter 重写：不覆盖 writeString 保留下标处理，仅用 processTextPosition 收集坐标做表格检测 |
| 2026-07-08 | 检索增强优化 — ① boostByDocumentTitle：实体匹配文档标题加分（查"材料学院"→材料学院文档+0.15）；② applyDocumentDiversity：每文档≤3 chunk 防单文档霸榜 |
| 2026-07-08 | 参考资料定位升级 — ① PDF 页码推断(pageStart/pageEnd)→chunk→SourceInfo→前端；② Word→PDF 预览(LibreOffice)；③ 前端 PdfViewer 组件(PDF.js渲染+页码定位+文字高亮)；④ GET /api/documents/{id}/content + /preview 接口；⑤ 纯文本降级模式 |
| 2026-07-08 | 前端 Markdown 渲染升级 — 手写正则→marked 库，支持表格/代码块等完整 GFM |
| 2026-07-07 | DOCX/DOC 文件支持（Apache POI，表格→Markdown表格保留结构） |
| 2026-07-07 | MinIO 对象存储（原始文件保存+下载预览） |
| 2026-07-07 | MD5 去重（相同文件拦截重复上传） |
| 2026-07-07 | SourceInfo 增加 documentId（前端可点击查看原文） |
| 2026-07-07 | Docker 数据迁移至 D 盘 |
| 2026-07-07 | Vue 3 前端（TDesign Chat，对话页+文档管理页） |

## 待完成

| 优先级 | 任务 | 说明 |
|--------|------|------|
| P2 | 对话标题重命名 | 左侧会话列表支持双击/右键重命名 |
| P2 | sessionId 缩短 | 当前 UUID 偏长，改用短 ID（如 6 位字母数字） |
| P2 | 文件访问 URL 安全 | `GET /api/documents/{id}/file` 改为使用随机 token，避免用户遍历 ID 访问他人文件 |
| P2 | 服务重启时 PROCESSING 状态补偿 | 扫描 status=PROCESSING 重新投 MQ |
| P3 | Embedding 重试（3次指数退避） | |
| P3 | 检索低分拦截（score < 0.5 不调 LLM） | |
| P3 | VectorStore 接口化（为 Redis Stack / Milvus 替换做准备） | |

## 面试要点

项目是黑马点评的技术延续：Redis 缓存、RabbitMQ 异步、MyBatis-Plus CRUD 都是同一个技术栈。面试时强调：多策略分块的设计思路、关键词加权的改进动机（纯向量检索的语义偏差问题）、DocumentConverter 的扩展性预留。
