# 校园智答 — 开发计划文档

> 基于RAG（检索增强生成）架构的校园知识库问答系统
>
> 技术栈：Spring Boot 3.2 + MyBatis-Plus + Redis + RabbitMQ + DeepSeek + DashScope + MinIO + Vue 3
>
> 开发日期：2026/07/06 — 2026/07/08
>
> **当前进度：所有 P1 任务已完成，P2/P3 优化进行中。**

---

## 目录

- [一、项目定位](#一项目定位)
- [二、项目结构](#二项目结构)
- [三、技术全景](#三技术全景)
- [四、核心架构](#四核心架构)
- [五、数据库设计](#五数据库设计)
- [六、API接口设计](#六api接口设计)
- [七、开发进度](#七开发进度)
- [八、待完成事项](#八待完成事项)
- [九、面试准备指南](#九面试准备指南)
- [十、环境搭建清单](#十环境搭建清单)
- [十一、分块策略详解](#十分块策略详解)
- [十二、已解决的问题清单](#十二已解决的问题清单)

---

## 一、项目定位

### 1.1 解决什么问题

校园规章制度、评奖评优政策、保研转专业条例等关键信息散落在各学院官网、通知公告中，学生查找效率低。

**校园智答** 将这些静态文档收录为知识库，学生用自然语言提问即可获得准确的答案 + 引用来源。

### 1.2 功能列表

| 编号 | 功能 | 说明 | 优先级 | 状态 |
|------|------|------|--------|:--:|
| F1 | 文档上传 | 支持 TXT/MD/PDF/DOCX/DOC | P0 | ✅ |
| F2 | 自动分块 | 多策略自适应（Markdown / Q&A / 中文结构 / 滑动窗口） | P0 | ✅ |
| F3 | 向量化 | DashScope text-embedding-v3，1024维 | P0 | ✅ |
| F4 | 语义检索 | 余弦相似度 + 关键词加权重排序 | P0 | ✅ |
| F5 | RAG问答 | 检索上下文 + Prompt模板 + LLM生成 | P0 | ✅ |
| F6 | 多轮对话 | Redis缓存对话历史，24h TTL | P0 | ✅ |
| F7 | 文档管理 | 列表查询、状态查看、删除 | P1 | ✅ |
| F8 | 对话历史 | 按会话查询历史记录 | P1 | ✅ |
| F9 | PDF解析 | 集成PDFBox提取文本 | P1 | ✅ |
| F10 | DOCX/DOC解析 | 集成POI，表格→Markdown表格 | P1 | ✅ |
| F11 | Vue 3 前端 | 对话页 + 文档管理页（TDesign Chat） | P2 | ✅ |
| F12 | MD5去重 | 相同文件拦截重复上传 | P1 | ✅ |
| F13 | MinIO对象存储 | 原始文件保存 + 下载预览 | P1 | ✅ |
| F14 | 多轮对话上下文 | Redis瘦身Q&A + 查询改写 | P1 | ✅ |
| F15 | 检索增强 | 文档标题匹配 + 多样性截断 | P1 | ✅ |
| F16 | 参考资料定位 | PdfViewer(PDF.js) + 页码定位 + 文字高亮 + Word→PDF预览 | P1 | ✅ |
| F17 | Markdown渲染 | marked库 + 表格CSS | P2 | ✅ |
| F18 | 表格感知分块 | chunker表格检测 + PDF坐标表格识别 | P1 | ✅ |

### 1.3 文档分类设计

| 分类 | 编码 | 示例文档 |
|------|------|---------|
| 规章制度 | POLICY | 学生手册、宿舍管理条例 |
| 评奖评优 | SCHOLARSHIP | 国家奖学金评定办法、三好学生评选细则 |
| 学业政策 | ACADEMIC | 保研推免办法、转专业规定、辅修管理办法 |
| 生活指南 | GUIDE | 校园卡使用指南、新生入学指南 |
| 其他 | OTHER | 通知类文件、临时性告知 |

### 1.4 与黑马点评项目的技术关联

| 黑马点评用到的技术 | 校园智答中的应用 |
|-------------------|-----------------|
| Redis 缓存 | 对话历史缓存 |
| RabbitMQ 异步 | 文档分块+向量化异步处理 |
| MyBatis-Plus CRUD | 文档/Chunk/对话记录的管理 |
| 全局异常处理 | 统一错误响应 |

---

## 二、项目结构

```
rag-campus-qa/
│
├── pom.xml
├── docker-compose.yml                       # MySQL + Redis + RabbitMQ + MinIO
├── CLAUDE.md
├── DEVELOPMENT_PLAN.md
├── README.md                                # 待完成
│
├── frontend/                                # Vue 3 前端
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.ts
│       ├── App.vue
│       ├── router/index.ts
│       ├── api/chat.ts, document.ts
│       ├── components/PdfViewer.vue
│       └── views/ChatView.vue, DocumentView.vue
│
├── src/main/resources/
│   ├── application.yaml
│   └── db/init.sql, migration.sql
│
├── src/main/java/com/rag/campus/
    ├── common/Result.java
    ├── config/{AppConfig, RabbitMQConfig, MinioConfig, WebMvcConfig}
    ├── entity/{Document, DocumentChunk, Conversation}
    ├── mapper/{DocumentMapper, DocumentChunkMapper, ConversationMapper}
    ├── dto/{ChatRequest, ChatResponse, DocumentUploadResult}
    ├── client/{DeepSeekClient, EmbeddingClient}
    ├── support/
    │   ├── DocumentConverter.java
    │   ├── DocumentChunker.java
    │   ├── VectorStore.java
    │   ├── RagPromptTemplate.java
    │   ├── MinioStorageService.java
    │   ├── MinioInitializer.java
    │   ├── OfficePreviewService.java
    │   └── impl/{PdfBoxConverter, PlainTextConverter, DocxConverter, MarkItDownConverter}
    ├── service/{DocumentService, RagService}
    │   └── impl/{DocumentServiceImpl, RagServiceImpl, DocumentProcessConsumer}
    └── controller/{DocumentController, ChatController}
```

---

## 三、技术全景

### 3.1 本地基础设施

| 工具 | 版本 | 用途 | 方式 |
|------|------|------|------|
| JDK | 17+ | Spring Boot 3.x | 本地 |
| MySQL | 8.0 | 持久化 | Docker |
| Redis | 7.x | 对话历史 | Docker |
| RabbitMQ | 3.12 | 异步处理 | Docker |
| MinIO | latest | 原始文件存储 | Docker |
| Node.js | 22+ | 前端构建 | 本地 |

### 3.2 远程API服务

| 服务 | 用途 |
|------|------|
| DeepSeek | LLM对话生成（chat-model: deepseek-chat） |
| DashScope | 文本向量化（text-embedding-v3, 1024维） |

### 3.3 Java依赖

| 依赖 | 版本 | 作用 |
|------|------|------|
| spring-boot-starter-web/data-redis/amqp | 3.2.0 | 基础设施 |
| mybatis-plus-spring-boot3-starter | 3.5.5 | ORM |
| hutool-all | 5.8.25 | JSON/MD5/字符串 |
| pdfbox | 2.0.30 | PDF |
| poi-ooxml / poi-scratchpad | 5.2.5 | DOCX/DOC |
| minio | 8.5.7 | 对象存储 |

---

## 四、核心架构

### 4.1 架构图

```
Vue 3 前端（TDesign Chat）
     │
     ├── POST /api/documents/upload ──→ MD5去重 → Converter → MySQL + MinIO → MQ → 异步分块+向量化
     │
     └── POST /api/chat/ask ──→ 查询改写 → Embedding → VectorStore搜索 → 关键词重排
                                   → Redis加载Q&A历史 → Prompt组装 → DeepSeek → 返回
```

### 4.2 关键设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 向量存储 | 内存ConcurrentHashMap | Demo数据量可控 |
| 分块策略 | 4级自适应 | 不同文档不同结构 |
| 文档转换 | DocumentConverter接口 | 策略模式，扩展不改旧代码 |
| 原始文件 | MinIO对象存储 | 下载预览，故障不影响检索 |
| 重复上传 | MD5哈希去重 | 相同文件拦截 |
| 前端 | Vue 3 + TDesign Chat | 打包嵌入Spring Boot |
| Word表格 | 遍历bodyElements → Markdown表格 | 保留行列结构，不被分块截断 |

---

## 五、数据库设计

- `tb_document`：id, title, category, department, content, file_type, content_hash(MD5), file_key(MinIO路径), preview_file_key(Word→PDF预览), status, chunk_count
- `tb_document_chunk`：id, document_id(FK CASCADE), chunk_index, chunk_text, page_start(页码定位), page_end, embedding(JSON), create_time
- `tb_conversation`：id, session_id, question, answer, sources(JSON), create_time

Redis: `rag:conversation:{sessionId}` → JSON [{role, content}], 24h TTL, 仅存瘦身Q&A

---

## 六、API接口设计

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/documents/upload | 上传文档 |
| GET | /api/documents | 文档列表 |
| GET | /api/documents/{id} | 文档详情 |
| GET | /api/documents/{id}/file | 下载/预览原始文件（?download=true 强制下载） |
| GET | /api/documents/{id}/preview | PDF 预览（PDF原始/Word转PDF） |
| GET | /api/documents/{id}/content | 文档完整文本+chunks（参考资料抽屉用） |
| DELETE | /api/documents/{id} | 删除文档 |
| POST | /api/chat/ask | 提问 |
| GET | /api/chat/history/{sessionId} | 对话历史 |

SourceInfo 含 documentId + fileType + pageStart/pageEnd + chunkIndex + snippet，前端据此选择 PDF/文本模式展示。

---

## 七、开发进度

### 已完成 ✅

| 日期 | 内容 |
|------|------|
| 7/6 | Docker环境、DeepSeek+DashScope调通、PDFBox、DocumentConverter、分块器、Embedding、关键词重排、RAG全链路、多轮对话、content清空、文档缓存 |
| 7/7 | DOCX/DOC支持（POI+表格→Markdown）、MinIO对象存储、MD5去重、参考资料documentId+去重、Vue 3前端（TDesign Chat对话页+文档管理页）、SPA路由回退、文档删除（级联）、前端历史加载、Docker数据迁移D盘 |
| 7/8 | **多轮对话上下文**（Redis瘦身+查询改写）、**表格分块修复**（chunker表格感知+PdfBoxConverter表格检测）、**检索增强**（文档标题匹配+多样性截断）、**参考资料定位升级**（PDF页码推断+Word→PDF预览+PdfViewer组件+文本降级模式）、**前端Markdown升级**（marked库表格渲染） |

---

## 八、待完成事项

### P2 — 体验优化

| # | 任务 | 说明 |
|---|------|------|
| 1 | 对话标题重命名 | 双击/右键编辑 |
| 2 | sessionId缩短 | 6位字母数字 |
| 3 | 文件URL安全 | id→随机token，防遍历 |
| 4 | README.md | 项目说明 |
| 5 | PROCESSING补偿 | 重启扫描重新投MQ |

### P3 — 健壮性

| # | 任务 |
|---|------|
| 6 | Embedding重试（3次指数退避） |
| 7 | 检索低分拦截（score<0.5不调LLM） |
| 8 | VectorStore接口化 |

---

## 九、面试准备指南

### 项目一句话

> 校园智答是一个基于RAG架构的校园知识库问答系统。把学校规章制度等静态文档分块向量化，学生自然语言提问→语义检索→LLM生成带来源的回答。Spring Boot 3.2 + MyBatis-Plus + Redis + RabbitMQ + MinIO + Vue 3，DeepSeek + DashScope。

### 核心面试点

- **RAG链路**：问题→Embedding→向量检索(余弦相似度)→关键词加权→Prompt→LLM→标注来源
- **分块策略**：4级自适应（MD/Q&A/中文结构/滑动窗口），DOCX表格→Markdown表格
- **去重**：MD5哈希，content_hash列
- **DocumentConverter**：策略模式，Spring自动注入，加格式不改代码
- **多轮对话**：Redis瘦身Q&A(去chunk) + 查询改写 + MySQL持久化
- **检索增强**：关键词加权 + 文档标题实体匹配 + 文档多样性截断
- **参考资料定位**：PDF页码推断 + PdfViewer(PDF.js)渲染 + 文字高亮 + Word→LibreOffice转PDF预览

---

## 十、环境搭建

```bash
# Docker 中间件
docker compose up -d    # MySQL + Redis + RabbitMQ + MinIO

# 数据库迁移（已有DB）
docker exec -i rag-mysql mysql -uroot -proot rag_campus < src/main/resources/db/migration.sql

# 启动后端
mvn spring-boot:run     # → http://localhost:8081

# 前端开发模式
cd frontend && npm run dev   # → http://localhost:5173

# 前端打包
cd frontend && npm run build # → target/classes/static/
```

---

## 十一、分块策略

4级自适应优先级：
1. Markdown标题（`#`/`##`/`###`）
2. Q&A格式（`Q1`/`Q2`等，跳过目录）
3. 中文结构（POLICY/SCHOLARSHIP/ACADEMIC + "第X章"，过滤总结句）
4. 滑动窗口兜底（800字/块，200字重叠）

---

## 十二、已解决的问题清单

| # | 问题 | 解决方案 |
|---|------|---------|
| 1 | PDF二进制乱码 | PDFBox流式加载 |
| 2 | MP selectById与Function不匹配 | 桥接方法 |
| 3 | DashScope单次≤10条 | embedBatch自动分批+延迟 |
| 4 | 检索排序不准 | chunk扩大+关键词加权 |
| 5 | 目录被当成正文 | Q&A切分跳过短section |
| 6 | 总结句被当标题 | 长度>60或含"介绍/说明"过滤 |
| 7 | 同文档重复查DB 16次 | HashMap请求级缓存 |
| 8 | content大字段拖慢查询 | 处理完清空 |
| 9 | Word表格结构丢失 | bodyElements遍历→Markdown表格 |
| 10 | 重复上传 | MD5去重 |
| 11 | 老文档无原始文件404 | 返回提示HTML |
| 12 | 参考资料同文档重复 | buildSources按documentId去重 |
| 13 | 前端刷新对话丢失 | 从后端API加载历史 |
| 14 | 参考资料重复文档显示 | 按documentId去重保留最高分 |
| 15 | 跨文档语义污染 | boostByDocumentTitle + applyDocumentDiversity |
| 16 | 表格跨chunk表头丢失 | chunkBySlidingWindow 表格感知补表头 |
| 17 | PDF表格行列丢失 | PdfBoxConverter 坐标分析→Markdown表格 |
| 18 | PDF下标字符游离 | 不覆盖writeString，保留下标处理 |
| 19 | Word文档PDF预览 | OfficePreviewService(LibreOffice) |
| 20 | Spring Boot不识别.mjs | WebMvcConfig MIME映射 |

---

> **最后更新：2026/07/08**
>
> **当前状态：** 所有 P1 任务已完成。P2/P3 体验优化进行中。
