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

## License

MIT
