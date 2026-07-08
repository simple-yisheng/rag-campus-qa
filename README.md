# 校园智答 — RAG 校园知识库问答系统

基于 **RAG（检索增强生成）** 架构的校园知识库问答系统。上传学校规章制度、评奖评优政策等文档，学生用自然语言提问即可获得准确答案 + 可点击查看的原文引用。

> 🎓 **面试项目**：Spring Boot 3.2 + MyBatis-Plus + Redis + RabbitMQ + DeepSeek + DashScope + MinIO + Vue 3

---

## 功能演示

| 文档上传与处理 | RAG 智能问答 | 参考资料定位 |
|:---:|:---:|:---:|
| 支持 PDF/Word/TXT/MD | 多轮对话 + 查询改写 | 点击参考资料 → 侧边抽屉 |
| MD5 去重 + 表格感知分块 | 检索增强（关键词/标题/多样性） | PDF.js 渲染 + 文字高亮定位 |
| Word 自动转 PDF 预览 | DeepSeek LLM 生成 | 页码精确跳转 |

---

## 技术栈

| 层级 | 技术 | 说明 |
|:---|:---|:---|
| **后端框架** | Spring Boot 3.2 | REST API + MQ 消费者 |
| **ORM** | MyBatis-Plus 3.5 | CRUD + 自定义查询 |
| **数据库** | MySQL 8.0 | 文档 / 分块 / 对话记录持久化 |
| **缓存** | Redis 7 | 对话历史（24h TTL，瘦身 Q&A） |
| **消息队列** | RabbitMQ 3.12 | 文档分块 + 向量化异步处理 |
| **对象存储** | MinIO | 原始文件 + Word→PDF 预览存储 |
| **LLM** | DeepSeek (deepseek-chat) | 问答生成 + 查询改写 |
| **Embedding** | DashScope (text-embedding-v3) | 1024 维文本向量化 |
| **PDF 处理** | PDFBox 2.x + PDF.js v4 | PDF 文本提取 / 前端渲染 |
| **Word 处理** | Apache POI 5.x + LibreOffice | DOCX/DOC 提取 / PDF 预览转换 |
| **前端** | Vue 3 + TDesign + marked | SPA，Vite 构建，嵌入 Spring Boot |

---

## 快速启动

### 1. 克隆项目

```bash
git clone <your-repo-url>
cd rag-campus-qa
```

### 2. 启动 Docker 中间件

```bash
docker compose up -d
# MySQL 8.0:3306  |  Redis 7:6379  |  RabbitMQ 3.12:5672/15672  |  MinIO:9000/9001
```

数据库表结构自动初始化（`src/main/resources/db/init.sql`）。

### 3. 配置 API Key

创建 `src/main/resources/application-local.yaml`（已 gitignore）：

```yaml
deepseek:
  api-key: sk-your-deepseek-api-key

embedding:
  dashscope:
    api-key: sk-your-dashscope-api-key
```

> DeepSeek 注册：https://platform.deepseek.com  
> DashScope 注册：https://dashscope.aliyun.com（免费额度）

### 4. Word 预览支持（可选）

如需 Word 文档的 PDF 预览功能，安装 [LibreOffice](https://www.libreoffice.org/) 并确保 `soffice` 在 PATH 中，或在 `application.yaml` 中配置绝对路径：

```yaml
office:
  preview:
    libreoffice-path: C:\Program Files\LibreOffice\program\soffice.exe  # Windows 示例
```

### 5. 启动后端

```bash
mvn spring-boot:run
# → http://localhost:8081
```

### 6. 启动前端（开发模式）

```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173（API 自动代理到 8081）
```

生产部署时 `npm run build` 将前端打包到 `target/classes/static/`，由 Spring Boot 直接托管。

---

## 核心架构

```
┌─────────────────────────────────────────────────────────────┐
│  Vue 3 前端（TDesign Chat）                                   │
├─────────────────────────────────────────────────────────────┤
│  POST /api/documents/upload   │  POST /api/chat/ask          │
├───────────────────────────────┼──────────────────────────────┤
│  文档上传链路                  │  RAG 问答链路                  │
│  MD5去重 → Converter(策略)    │  查询改写(多轮上下文)           │
│  → MySQL(PENDING) + MinIO     │  → Embedding(向量化)           │
│  → Word LibreOffice转PDF      │  → VectorStore.search(宽窗口)  │
│  → RabbitMQ → 异步处理        │  → 关键词加权 → 文档标题匹配    │
│  → Chunker(4级自适应+表格感知) │  → 文档多样性截断 → 低分拦截    │
│  → Embedding → MySQL+向量索引 │  → Prompt组装 → DeepSeek生成   │
│                               │  → Redis(瘦身Q&A) + MySQL持久化│
└───────────────────────────────┴──────────────────────────────┘
```

### 分块策略（4 级自适应）

1. **Markdown 标题切分** — 检测 `#`/`##`/`###` 标题边界
2. **Q&A 格式切分** — 检测 `Q1`/`Q2` 标题，自动跳过目录页
3. **中文结构切分** — POLICY/SCHOLARSHIP/ACADEMIC 分类 + "第X章"检测
4. **滑动窗口兜底** — 800 字/块，200 字重叠，Markdown 表格感知（跨块自动补表头）

### 检索增强（4 层后处理）

| 策略 | 说明 |
|:---|:---|
| 关键词加权 | 查询词命中 chunk → 向量分 + 关键词命中率 × 0.2 |
| 文档标题匹配 | 查询含"材料学院" → 材料学院文档 chunk +0.15 |
| 文档多样性 | 每文档最多贡献 3 个 chunk，防止单文档霸榜 |
| 低分拦截 | 相似度 < 0.55 的 chunk 不参与 LLM 生成 |

---

## 项目结构

```
rag-campus-qa/
├── docker-compose.yml                    # MySQL + Redis + RabbitMQ + MinIO
├── pom.xml
├── README.md
├── CLAUDE.md                             # AI 辅助开发说明
├── DEVELOPMENT_PLAN.md                   # 开发计划与进度
│
├── frontend/                             # Vue 3 前端
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.ts / App.vue
│       ├── router/index.ts
│       ├── api/chat.ts, document.ts
│       ├── components/PdfViewer.vue       # PDF.js 渲染 + 高亮组件
│       └── views/ChatView.vue, DocumentView.vue
│
├── src/main/resources/
│   ├── application.yaml                  # 主配置（可提交）
│   ├── application-local.yaml            # API Key（gitignored）
│   └── db/init.sql, migration.sql
│
└── src/main/java/com/rag/campus/
    ├── common/Result.java
    ├── config/{AppConfig, RabbitMQConfig, MinioConfig, WebMvcConfig}
    ├── entity/{Document, DocumentChunk, Conversation}
    ├── mapper/{DocumentMapper, DocumentChunkMapper, ConversationMapper}
    ├── dto/{ChatRequest, ChatResponse, DocumentUploadResult}
    ├── client/{DeepSeekClient, EmbeddingClient}
    ├── support/
    │   ├── DocumentConverter.java          # 转换器接口（策略模式）
    │   ├── DocumentChunker.java            # 4级自适应分块器
    │   ├── VectorStore.java                # 内存向量索引
    │   ├── RagPromptTemplate.java          # Prompt 模板
    │   ├── MinioStorageService.java        # MinIO 对象存储
    │   ├── OfficePreviewService.java       # Word→PDF 预览（LibreOffice）
    │   └── impl/{PdfBoxConverter, PlainTextConverter, DocxConverter}
    ├── service/{DocumentService, RagService}
    │   └── impl/{DocumentServiceImpl, RagServiceImpl, DocumentProcessConsumer}
    └── controller/{DocumentController, ChatController}
```

---

## API 接口

### 文档管理

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| POST | `/api/documents/upload` | 上传文档（form-data: file, title?, category, department?） |
| GET | `/api/documents` | 文档列表 |
| GET | `/api/documents/{id}` | 文档详情 |
| GET | `/api/documents/{id}/file` | 下载/预览原始文件（?download=true 强制下载） |
| GET | `/api/documents/{id}/preview` | PDF 预览（PDF 原生 / Word→PDF 转换） |
| GET | `/api/documents/{id}/content` | 文档完整文本 + chunk 列表（参考资料抽屉用） |
| DELETE | `/api/documents/{id}` | 删除文档（级联 chunks + MinIO + 向量索引） |

### 对话

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| POST | `/api/chat/ask` | RAG 问答 `{ "sessionId?": "xxx", "question": "..." }` |
| GET | `/api/chat/history/{sessionId}` | 查询对话历史 |

---

## 数据库

| 表 | 说明 |
|:---|:---|
| `tb_document` | 文档元信息（标题、分类、MD5、MinIO路径、状态） |
| `tb_document_chunk` | 文档分块（文本、向量、PDF页码） |
| `tb_conversation` | 对话记录（session_id、问答、引用来源 JSON） |

Redis: `rag:conversation:{sessionId}` → 瘦身 Q&A 数组（仅原始问答，不含 chunk），24h TTL

---

## 面试要点

该项目使用传统后端技术（Redis 缓存、RabbitMQ 异步、MyBatis-Plus CRUD 为同一技术栈），适合在面试中展示以下亮点：

1. **RAG 全链路**：问题 → 查询改写 → Embedding → 余弦相似度检索 → 关键词加权 → 文档归属匹配 → 多样性截断 → 低分拦截 → Prompt 组装 → LLM 生成 → 瘦身持久化
2. **DocumentConverter 策略模式**：新增格式只需添加实现类，Spring 自动注入，符合开闭原则
3. **4 级自适应分块**：Markdown → Q&A → 中文结构 → 滑动窗口，不同文档自动匹配最优策略
4. **多轮对话**：Redis 瘦身存储 + 查询改写（LLM 补全追问上下文）+ MySQL 持久化
5. **异步处理**：RabbitMQ 解耦上传与处理，分块 + 向量化异步执行
6. **表格处理**：DocxConverter 遍历 bodyElements 保留表格结构，PdfBoxConverter 坐标分析检测表格，Chunker 跨块补表头

---

## 核心流程详解

### 一、文档上传链路

```
用户上传文件（POST /api/documents/upload）
  │
  ├─ 1. MD5 去重
  │    计算文件字节 MD5 → 查 tb_document.content_hash
  │    └─ 已存在且 status=DONE → 拦截，返回 "该文件已上传过"
  │
  ├─ 2. 文本提取（DocumentConverter 策略模式）
  │    根据文件扩展名匹配转换器：
  │    .pdf  → PdfBoxConverter    （PDFBox 提取，坐标分析检测表格→Markdown）
  │    .docx → DocxConverter      （POI 遍历 bodyElements，表格→Markdown 表格）
  │    .doc  → DocxConverter      （HWPF 旧格式，基础提取）
  │    .txt  → PlainTextConverter （纯文本原样读取）
  │    .md   → PlainTextConverter
  │
  ├─ 3. 写入 MySQL
  │    INSERT tb_document (title, category, content=全文, content_hash=MD5, status=PENDING)
  │    获取自增 ID
  │
  ├─ 4. 存储原始文件到 MinIO
  │    minioStorage.upload(docId, filename, bytes, contentType)
  │    → fileKey = "documents/{docId}/{filename}"
  │    └─ MinIO 故障不影响后续流程（try-catch 容错）
  │
  ├─ 5. Word→PDF 预览（仅 DOCX/DOC）
  │    OfficePreviewService.convertWordToPdf()
  │    → 调用 LibreOffice headless --convert-to pdf
  │    → 上传到 MinIO，存 previewFileKey
  │    └─ 失败不阻塞上传（try-catch 容错，前端降级为纯文本模式）
  │
  └─ 6. 发送 MQ 消息
       rabbitTemplate.convertAndSend(rag.document.exchange, rag.document.process, docId)
       → 异步处理分块 + 向量化
       → 返回前端 "上传成功，后台处理中"
```

### 二、文档异步处理链路

```
RabbitMQ 消费者收到 docId
  │
  ├─ 1. 更新状态 PROCESSING
  │
  ├─ 2. 分块（DocumentChunker — 4 级自适应）
  │    ┌─ 检测 Markdown 标题（#/##/###）   → chunkByMarkdown()
  │    ├─ 检测 Q&A 格式（Q1/Q2/Q3...）     → chunkByQA() + 目录过滤
  │    ├─ 中文结构（第X章/第X条）           → chunkByStructure()
  │    └─ 兜底：滑动窗口（800字/块，200重叠） → chunkBySlidingWindow()
  │                                            └─ Markdown 表格感知：跨 chunk 自动补表头
  │
  ├─ 3. PDF 页码推断（仅 PDF 文件）
  │    对每个 chunk 文本，反查 PDF 各页文字内容
  │    → 子串匹配定位 chunk 出自第几页
  │    → 写入 pageStart / pageEnd
  │
  ├─ 4. 批量向量化（EmbeddingClient）
  │    DashScope text-embedding-v3，1024 维
  │    单次 API 最多 10 条 → embedBatch() 自动分批 + 200ms 延迟
  │
  ├─ 5. 写入分块表 + 加载向量索引
  │    INSERT tb_document_chunk (documentId, chunkIndex, chunkText, pageStart, pageEnd, embedding)
  │    VectorStore.addBatch() → ConcurrentHashMap<chunkId, float[]>
  │
  ├─ 6. 清理 + 更新状态
  │    doc.content = ""       （原文已分块，清空避免大字段拖慢列表查询）
  │    doc.status = "DONE"
  │    doc.chunkCount = N
  └─    缓存原文到 displayContentCache（供参考资料抽屉快速展示）
```

### 三、RAG 问答链路

```
用户提问（POST /api/chat/ask）
  │
  ├─ 1. 生成/复用 sessionId（首次自动生成 8 位 UUID 前缀）
  │
  ├─ 2. 加载对话历史（Redis 瘦身格式）
  │    Key: rag:conversation:{sessionId}
  │    Value: [{"role":"user","content":"原问题"}, {"role":"assistant","content":"回答"}]
  │    └─ 仅存原始 Q&A，不含 system prompt 和 chunk（避免历史膨胀 + 旧 chunk 干扰）
  │    └─ 兼容旧格式：检测到首条 role=system → 自动清空并重新开始
  │
  ├─ 3. 查询改写（仅当有历史时触发）
  │    RagPromptTemplate.buildRewritePrompt(历史Q&A, 当前追问)
  │    → DeepSeekClient.rewriteQuery()（轻量 LLM 调用，temperature=0.1, max_tokens=200）
  │    例："那第三章呢" → "《XX文档》第三章的具体内容是什么"
  │    └─ 改写失败静默降级：使用原始问题继续
  │
  ├─ 4. 向量化 + 语义检索
  │    EmbeddingClient.embed(searchQuery) → 1024维向量
  │    VectorStore.search(queryVector, topK×3) → 大窗口召回候选集
  │    └─ 余弦相似度遍历 ConcurrentHashMap，O(n)
  │
  ├─ 5. 检索增强（4 层后处理）
  │    ┌─ ① rerankByKeywordBoost()：提取关键词 → 计算 chunk 命中率 → +0.2×命中率
  │    ├─ ② boostByDocumentTitle()：提取查询中的机构实体（XX学院等）
  │    │      匹配文档标题 → 第一个实体匹配 +0.15，后续递减
  │    ├─ ③ applyDocumentDiversity()：每文档最多保留 3 个 chunk，防止单文档霸榜
  │    │      超出部分丢弃，用其他文档的 chunk 补位到 topK
  │    └─ ④ filterByMinScore()：相似度 < 0.55 的 chunk 直接丢弃（低分拦截）
  │           全部被过滤时退回原始结果，避免空响应
  │
  ├─ 6. Prompt 组装
  │    System: RagPromptTemplate.buildSystemPrompt()（角色定义 + 回答规则）
  │    History: Redis 加载的瘦身 Q&A（仅原始问答，不带旧 chunk）
  │    User: "--- 资料片段 N（来源：《文档名》，匹配度: 0.XX）---\n[chunk文本]\n\n用户问题：xxx"
  │
  │    消息结构：[system(fresh)] + [瘦身Q&A历史] + [当前user(含本次chunk)]
  │
  ├─ 7. LLM 生成
  │    DeepSeekClient.chatWithHistory(messages)
  │    → POST https://api.deepseek.com/chat/completions
  │    → model: deepseek-chat, temperature: 0.3, max_tokens: 4096
  │
  ├─ 8. 构建引用来源
  │    RagPromptTemplate.buildSources(hits)
  │    → 按 documentId 去重（每文档只保留最高分 chunk）
  │    → SourceInfo: { documentId, title, fileType, chunkIndex, pageStart, pageEnd, score, snippet }
  │
  ├─ 9. 持久化
  │    Redis: 追加本轮瘦身 Q&A → ttl 刷新 24h → 最多保留 maxHistory 轮
  │    MySQL: INSERT tb_conversation (sessionId, question, answer, sources, createTime)
  │
  └─ 10. 返回 ChatResponse { sessionId, answer, sources[] }
```

### 四、多轮对话内存设计

```
┌─ 短期记忆（Redis）─────────────────────────────────────────┐
│ Key: rag:conversation:{sessionId}                          │
│ 格式: [{"role":"user","content":"奖学金条件"},              │
│        {"role":"assistant","content":"需GPA≥3.5..."}]      │
│                                                             │
│ 特点: 仅存原始 Q&A 文本（去 system prompt、去 chunk）        │
│ TTL: 24h（过期自动遗忘）                                    │
│ 上限: 10 轮（超出截断旧轮次）                                │
│                                                             │
│ 用途①: 查询改写 — 补全追问上下文                             │
│ 用途②: 对话连贯 — 拼入 LLM messages 维持多轮感知            │
└─────────────────────────────────────────────────────────────┘

┌─ 长期记忆（MySQL tb_conversation）──────────────────────────┐
│ 每轮一条记录: sessionId + question + answer + sources       │
│ 持久存储，不过期                                            │
│ 用途: 前端对话历史列表 / 未来数据分析                        │
└─────────────────────────────────────────────────────────────┘
```

### 五、参考资料定位流程

```
用户点击参考资料标题
  │
  ├─ 判断文档类型
  │    ┌─ PDF 文件 且 有 pageStart → PDF 模式
  │    │    └─ PdfViewer 组件（pdfjs-dist v4 渲染）
  │    │        ├─ DPR 高清渲染（canvas × devicePixelRatio，CSS 缩回）
  │    │        ├─ 白底渲染防黑屏（渲染前后填充 + 像素检测 + 离屏兜底）
  │    │        ├─ 页码定位（initialPage 直接跳转）
  │    │        ├─ 范围渲染（renderRadius：只渲染目标页附近页面）
  │    │        └─ 文字高亮（提取 PDF 文字层，匹配 snippet 关键词 → 黄色标记）
  │    │
  │    └─ Word 文件 或 无页码 → 降级为纯文本模式
  │         ├─ GET /api/documents/{id}/content（从 chunks 拼接全文）
  │         ├─ findNormalizedRange() 去空白匹配目标 chunk
  │         └─ <mark> 标签高亮对应文本段 + 自动滚动
  │
  └─ PDF 预览来源
       PDF 文件: 直接使用 MinIO 原始文件（fileKey）
       Word 文件: 使用 LibreOffice 生成的 PDF 预览（previewFileKey）
       无预览: 降级为纯文本模式
```

### 六、PDF 表格处理全链路

```
PDF 文件上传
  │
  ├─ PdfBoxConverter.convert()
  │    ├─ processTextPosition() 收集每个字符的坐标(x, y, fontSize, page)
  │    │   └─ 调 super 让 PDFBox 原生 writeString 处理文本（保留下标合并）
  │    │
  │    └─ injectMarkdownTables(normalText)
  │         ├─ 按 Y 坐标分组为行（4pt 容差）
  │         ├─ 行内按 X 间隙检测列（gap > 2.5×平均字宽 → 新列）
  │         ├─ 连续 N 行同列数 + X 对齐 → 判定为表格
  │         ├─ 在原始文本中定位表格区域（首尾行文本匹配）
  │         └─ 替换为 Markdown 表格格式（\|...\| + \|---\|）
  │
  ├─ DocumentChunker.chunk()
  │    ├─ 按 Markdown 标题切分（表格在标题 section 内）
  │    ├─ section > chunkSize → chunkBySlidingWindow()
  │    └─ 滑动窗口表格感知：跨 chunk 补表头+分隔线
  │
  └─ 前端渲染
       marked.parse() → <table> HTML → CSS 表格样式（蓝灰表头/斑马纹）
```

### 七、Word→PDF 预览流程

```
Word 文件上传
  │
  ├─ DocxConverter 提取文本（保留表格为 Markdown）
  │
  └─ OfficePreviewService.convertWordToPdf()
       ├─ 创建临时目录
       ├─ 写入原始 Word 文件
       ├─ 执行: soffice --headless --convert-to pdf --outdir {temp} {input}
       ├─ 超时 60s → 强制终止
       ├─ 读取生成的 PDF → 上传 MinIO → 存 previewFileKey
       └─ 清理临时文件
```

---

## License

MIT
