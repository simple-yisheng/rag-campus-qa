# 校园智答 — RAG 校园知识库问答系统

基于 **RAG（检索增强生成）** 架构的校园知识库问答系统。上传学校规章制度、评奖评优政策等文档，学生用自然语言提问即可获得准确答案 + 可点击查看的原文引用。

> 🎓 **面试项目**：Spring Boot 3.2 + MyBatis-Plus + Redis + RabbitMQ + DeepSeek + DashScope + MinIO + Vue 3

---

## 功能演示

| 文档上传与处理 | RAG 智能问答 | 参考资料定位 |
|:---:|:---:|:---:|
| 支持 PDF/Word/TXT/MD | 多轮对话 + 查询改写 | 点击参考资料 → 侧边抽屉 |
| MD5 去重 + 表格感知分块 | 检索增强（关键词/标题/多样性） | PDF.js 渲染 + 文字高亮定位 |
| 4级自适应分块 + 文档审核 | 侧边栏收缩 + 动态标题 | MD marked 渲染 + PDF.js |
| **文档管理** | **会话管理** | **用户管理（管理员）** |
| 分页展示 + 弹窗上传 | 删除/重命名 + SSE流式 | CRUD + 密码重置 + 禁用/启用 |

---

## 技术栈

| 层级 | 技术 | 说明 |
|:---|:---|:---|
| **后端框架** | Spring Boot 3.2 | REST API + MQ 消费者 |
| **ORM** | MyBatis-Plus 3.5 | CRUD + 自定义查询 |
| **安全认证** | Spring Security + JWT | 无状态认证，BCrypt 密码加密，角色权限分离 |
| **数据库** | MySQL 8.0 | 文档 / 分块 / 会话 / 消息持久化 |
| **缓存** | Redis 7 | 对话上下文 + 历史/会话列表热缓存 |
| **消息队列** | RabbitMQ 3.12 | 文档分块 + 向量化异步处理 |
| **对象存储** | MinIO | 原始文件 + Word→PDF 预览存储 |
| **向量数据库** | Milvus 2.4（可选） | 向量存储与语义检索，IVF_FLAT + COSINE |
| **LLM** | DeepSeek (deepseek-chat) | 问答生成（SSE 流式）+ 查询改写 |
| **Embedding** | DashScope (text-embedding-v3) | 1024 维文本向量化 |
| **多模态** | 通义千问 VL (qwen-vl-plus) | PDF 扫描件 OCR + 嵌入图片描述 |
| **PDF 处理** | PDFBox 2.x + PDF.js v4 | PDF 文本提取 / 表格检测 / 图片提取 / 前端渲染 |
| **Word 处理** | Apache POI 5.x + LibreOffice | DOCX/DOC 提取 / PDF 预览转换 |
| **前端** | Vue 3 + TDesign + marked | SPA，Vite 构建，嵌入 Spring Boot，SSE 流式渲染 |

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
┌──────────────────────────────────────────────────────────────────┐
│  Vue 3 前端（TDesign Chat，SSE 流式渲染）                          │
├──────────────────────────────────────────────────────────────────┤
│  POST /api/documents/upload  │  POST /api/chat/ask/stream (SSE)  │
├──────────────────────────────┼───────────────────────────────────┤
│  文档上传链路                 │  RAG 问答链路                       │
│  MD5去重 → Converter(策略)   │  查询改写(多轮上下文)                │
│  → MySQL + MinIO             │  → Embedding(向量化)                │
│  → 审核(USER→PENDING→ADMIN) │  → Milvus/HashMap.search(宽窗口)    │
│  → Word LibreOffice转PDF     │  → 领域词+同义词关键词加权           │
│  → RabbitMQ → 异步处理       │  → 意图分类标题调整(±0.22)          │
│  → Chunker(4级自适应)        │  → 绝对+相对双阈值过滤               │
│  → Embedding → MySQL+Milvus  │  → 文档多样性截断(≤3/doc)           │
│                              │  → Prompt组装 → DeepSeek SSE 流式   │
│                              │  → Redis(瘦身Q&A) + MySQL持久化     │
└──────────────────────────────┴───────────────────────────────────┘
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
│       ├── api/index.ts, auth.ts, chat.ts, document.ts
│       ├── components/PdfViewer.vue       # PDF.js 渲染 + 高亮组件
│       └── views/{ChatView,DocumentView,UserManageView,LoginView,RegisterView,LayoutView}
│
├── src/main/resources/
│   ├── application.yaml                  # 主配置（可提交）
│   ├── application-local.yaml            # API Key（gitignored）
│   └── db/init.sql, migration.sql
│
└── src/main/java/com/rag/campus/
    ├── common/{Result, GlobalExceptionHandler}
    ├── config/{AppConfig, RabbitMQConfig, MinioConfig, MilvusConfig, WebMvcConfig, SecurityConfig}
    ├── security/{JwtUtil, JwtAuthFilter}
    ├── entity/{User, Document, DocumentChunk, ConversationSession, ConversationMessage}
    ├── mapper/{UserMapper, DocumentMapper, DocumentChunkMapper, ConversationSessionMapper, ConversationMessageMapper}
    ├── dto/{ChatRequest, ChatResponse, DocumentUploadResult, LoginRequest, RegisterRequest, LoginResponse}
    ├── client/{DeepSeekClient, EmbeddingClient, QwenVisionClient}
    ├── support/
    │   ├── DocumentConverter.java          # 转换器接口（策略模式）
    │   ├── DocumentChunker.java            # 4级自适应分块器
    │   ├── VectorStore.java                # 向量存储接口
    │   ├── InMemoryVectorStore.java        # 内存向量实现（默认）
    │   ├── MilvusVectorStore.java          # Milvus 向量实现（生产）
    │   ├── RagPromptTemplate.java          # Prompt 模板
    │   ├── MinioStorageService.java        # MinIO 对象存储
    │   ├── OfficePreviewService.java       # Word→PDF 预览（LibreOffice）
    │   └── impl/{PdfBoxConverter, PlainTextConverter, DocxConverter, MarkItDownConverter}
    ├── service/{DocumentService, RagService, UserService}
    │   └── impl/{DocumentServiceImpl, RagServiceImpl, UserServiceImpl, DocumentProcessConsumer}
    └── controller/{AuthController, DocumentController, ChatController, AdminController}
```

---

## API 接口

### 认证

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| POST | `/api/auth/register` | 注册 `{ "username","password","confirmPassword" }` |
| POST | `/api/auth/login` | 登录 `{ "username","password" }` → `{ "token","username","role" }` |
| GET | `/api/auth/me` | 获取当前用户信息（需 Bearer Token） |

### 文档管理

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| POST | `/api/documents/upload` | 上传文档（form-data: file, title?, category, department?） |
| GET | `/api/documents` | 文档列表（分页，?page=1&size=10） |
| GET | `/api/documents/{id}` | 文档详情 |
| GET | `/api/documents/{id}/file` | 下载/预览原始文件（?download=true 强制下载） |
| GET | `/api/documents/{id}/preview` | PDF 预览（PDF 原生 / Word→PDF 转换） |
| GET | `/api/documents/{id}/content` | 文档完整文本 + chunk 列表（参考资料抽屉用） |
| DELETE | `/api/documents/{id}` | 删除文档（级联 chunks + MinIO + 向量索引） |
| PUT | `/api/documents/{id}/review` | 审核文档 `{ "approved": true/false }` |

### 对话

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| POST | `/api/chat/ask` | RAG 问答（同步） `{ "sessionId?": "xxx", "question": "..." }` |
| POST | `/api/chat/ask/stream` | RAG 问答（**SSE 流式**）— 打字机效果，`text/event-stream` |
| GET | `/api/chat/sessions` | 当前用户的会话列表 |
| GET | `/api/chat/history/{sessionId}` | 查询指定会话的对话历史 |
| DELETE | `/api/chat/sessions/{sessionId}` | 删除会话（含关联消息+缓存） |
| PUT | `/api/chat/sessions/{sessionId}` | 重命名会话 `{ "title": "新标题" }` |

### 管理（管理员）

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| GET | `/api/admin/vector-stats` | 向量存储统计 |
| GET | `/api/admin/users?page=1&size=10` | 用户列表（分页） |
| POST | `/api/admin/users` | 创建用户 `{ "username","password","role" }` |
| PUT | `/api/admin/users/{id}` | 编辑用户（用户名/角色/状态） |
| PUT | `/api/admin/users/{id}/password` | 重置密码 `{ "password" }` |
| DELETE | `/api/admin/users/{id}` | 删除用户（不能删管理员） |
| PUT | `/api/documents/{id}/review` | 审核文档 `{ "approved": true/false }` |

---

## 数据库

| 表 | 说明 |
|:---|:---|
| `tb_user` | 用户（用户名、BCrypt 密码、USER/ADMIN 角色） |
| `tb_document` | 文档元信息（标题、分类、MD5、MinIO路径、状态、上传者） |
| `tb_document_chunk` | 文档分块（文本、向量、PDF页码） |
| `tb_conversation_session` | 会话（8位短ID、用户、标题、活跃时间） |
| `tb_conversation_message` | 对话消息（session_id、问答、引用来源 JSON） |

Redis 缓存：
- `rag:conversation:{sid}` — 对话上下文（瘦身 Q&A），24h TTL
- `rag:history:{sid}` — 历史消息缓存，5min TTL，新消息主动失效
- `rag:sessions:user:{uid}` — 会话列表缓存，5min TTL

---

## 面试要点

1. **RAG 全链路**：查询改写 → Embedding → Milvus/COSINE 检索 → 领域词+同义词加权 → 意图分类标题调整 → 双阈值过滤 → 多样性截断 → Prompt 组装 → **SSE 流式生成** → 来源去重限制
2. **检索策略**：62 领域词库 + 16 组同义词映射 + `QueryTerm` 带权重模型 + 考试/综测/竞赛意图分类 + 实体标题匹配（+0.15）+ 意图交叉降权（-0.18）+ 名录加成（+0.22）+ 绝对 0.55 + 相对 topScore×0.8 双阈值。30 条真实用例评测：Hit Rate 93.3%，MRR 0.933；真实 Embedding 语义检索预估 Hit Rate 97%+
3. **向量存储**：`VectorStore` 接口 → `InMemoryVectorStore`（开发/Demo）+ `MilvusVectorStore`（生产），策略模式，一行配置切换，启动自动迁移
4. **DocumentConverter 策略模式**：自定义转换器接口，已有 6 种实现（PDF/Word/TXT/MD + 扫描件 OCR + 图片描述），新增格式只需加实现类
5. **多模态处理**：扫描件 PDF → 渲染页面为图片 → 通义千问 VL OCR 逐页识别；嵌入图片 → 提取 → VL 描述 → 注入 chunk
6. **SSE 流式回答**：DeepSeek stream → RestTemplate ResponseExtractor → SseEmitter 独立线程 → fetch ReadableStream 前端打字机
7. **4 级自适应分块**：Markdown 标题 → Q&A 格式 → 中文结构 → 滑动窗口，含 Markdown 表格感知跨块补表头
8. **文档审核权限**：USER 上传→PENDING→ADMIN 通过/驳回→MQ 处理→入向量库，普通用户不可检索未审核文档
9. **多轮对话**：Redis 瘦身存储（仅原始 Q&A，去 chunk）+ 查询改写（LLM 补全上下文）+ MySQL 持久化
10. **缓存设计**：3 种 Redis Key + 读 miss 回填 + 写主动失效 + MySQL 兜底

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

## 检索质量评测

### 评测数据集

基于西北工业大学真实的校园规章文档，设计了 **30 条评测用例**，覆盖 5 个领域：

| 类别 | 数量 | 难度 easy/medium/hard | 示例问题 |
|:---|:---|:---|:---|
| POLICY（考试管理） | 6 条 | 1 / 5 / 0 | 考试作弊会受到什么处分？ |
| ACADEMIC（综测/竞赛/培养方案） | 10 条 | 4 / 4 / 2 | 材料学院和软件学院的综测计算方式有什么区别？ |
| GUIDE（校园生活/四六级） | 4 条 | 3 / 1 / 0 | 长安校区的宿舍床多大？ |
| SCHOLARSHIP（奖学金） | 7 条 | 2 / 3 / 2 | 三星奖学金和国家奖学金能同时拿吗？ |
| OTHER（体测/评教/社会实践） | 4 条 | 1 / 3 / 0 | 学生网上评教中"优秀"评价的比例有什么限制？ |

### 评测指标

| 指标 | 含义 | 计算方式 |
|:---|:---|:---|
| **Hit Rate**（命中率） | "用户问的问题，系统能不能查到？"——至少找回一个相关文档的查询比例 | 命中 ≥1 条的用例数 ÷ 总用例数 |
| **Recall@5**（召回率） | "该找的文档找齐了没有？"——前 5 条结果中找回的相关文档占期望文档的比例 | 命中的相关文档数 ÷ 期望相关文档总数 |
| **Precision@5**（精确率） | "返回的结果干不干净？"——前 5 条中有多少是真正相关的 | 命中的相关文档数 ÷ 5 |
| **MRR**（平均倒数排名） | "正确答案排得靠不靠前？"——第一个相关文档排名的倒数平均值 | Σ (1 ÷ 第一个命中排名) ÷ 用例总数 |

> **举例**：用户问"航空工业奖学金多少钱？"，期望文档是《奖学金设置摘要》：
> - 检索排第 1 位 → MRR = 1/1 = **1.0**（满分）
> - 检索排第 3 位 → MRR = 1/3 = **0.33**（用户得多翻两页）
> - 前 5 条都没找到 → Hit Rate=0, MRR=0

### 评测结果

#### 关键词检索基线（保守下限）

使用字符级 n-gram 关键词匹配替代语义向量，**测试检索后处理管道**（关键词加权、同义词扩展、实体匹配、阈值过滤、多样性截断）：

```
═════════════════════════════════════════════════════════════════════
  用例总数:  30         命中数: 28 (至少命中一个相关文档)
─────────────────────────────────────────────────────────────────
  Recall@5:   86.67%     — 前5条平均找回86.7%的期望文档
  Precision@5: 91.11%    — 前5条中91.1%是真正相关的
  MRR:       0.933       — 绝大多数正确答案排在第1位

  按难度统计:
  [easy]   11 条 | Hit Rate: 100%   | Recall@5: 100.0%
  [medium] 14 条 | Hit Rate:  86%   | Recall@5:  82.1%
  [hard]    5 条 | Hit Rate: 100%   | Recall@5:  70.0%

  按类别统计:
  POLICY         6 条 | Hit 100% | Recall 100.0%
  ACADEMIC      10 条 | Hit  80% | Recall  70.0%
  GUIDE          4 条 | Hit 100% | Recall 100.0%
  SCHOLARSHIP    6 条 | Hit  83% | Recall  72.2%
  OTHER          4 条 | Hit 100% | Recall 100.0%
═════════════════════════════════════════════════════════════════════
```

#### 真实 Embedding 验证（DashScope text-embedding-v3，30 条全量实测）

启动完整系统，上传 16 份真实校园规章文档，逐条验证全部 30 条用例：

```
                    关键词基线        真实 Embedding（实测）
─────────────────────────────────────────────────
HitRate@5             93.3%    →    100%   (30/30 全命中)
Recall@5              86.7%    →    93.3%
MRR                   0.933    →    0.950

按难度:
  easy    11 条 | 关键词 Hit 100%  Recall 100%  →  真实 Hit 100%  Recall 100%
  medium  14 条 | 关键词 Hit  86%  Recall  82%  →  真实 Hit 100%  Recall  93%
  hard     5 条 | 关键词 Hit 100%  Recall  70%  →  真实 Hit 100%  Recall  80%
```

> 详细验证记录见 `src/test/resources/eval/real-embedding-results.md`。

关键词评测中未命中的 4 条（#8 材料vs软件综测对比、#10 一票否决行为、#22 三星奖学金、#23 专项奖学金列表）全部被真实 Embedding 修正。最终 **30 条全命中，HitRate@5=100%，Recall@5=93.3%，MRR=0.950**。

### 运行评测

```bash
# 关键词检索基线（无需外部服务，纯本地运行）
mvn test -Dtest=RetrievalEvaluatorTest

# 全部测试（122 单元测试 + 1 评测）
mvn test
```

评测用例和代码位于 `src/test/java/com/rag/campus/eval/` 和 `src/test/resources/eval/test-cases.json`。

---

## License

MIT
