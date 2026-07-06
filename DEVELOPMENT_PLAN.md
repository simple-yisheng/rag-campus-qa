# 校园智答 — 开发计划文档

> 基于RAG（检索增强生成）架构的校园知识库问答系统
>
> 技术栈：Spring Boot 3.2 + MyBatis-Plus + Redis + RabbitMQ + DeepSeek + DashScope
>
> 开发周期：2026/07/08（周三）— 2026/07/12（周日）（5天）
>
> 简历投递目标：2026/07/15
>
> **当前进度：核心链路全部打通，已进入打磨阶段（Day 4-5）**

---

## 目录

- [一、项目定位](#一项目定位)
- [二、项目结构](#二项目结构)
- [三、技术全景](#三技术全景)
- [四、核心架构](#四核心架构)
- [五、数据库设计](#五数据库设计)
- [六、API接口设计](#六api接口设计)
- [七、开发进度（实际 vs 计划）](#七开发进度实际-vs-计划)
- [八、面试准备指南](#八面试准备指南)
- [九、环境搭建清单](#九环境搭建清单)
- [十、分块策略详解](#十分块策略详解)
- [十一、已解决的问题清单](#十一已解决的问题清单)

---

## 一、项目定位

### 1.1 解决什么问题

校园规章制度、评奖评优政策、保研转专业条例等关键信息散落在各学院官网、通知公告中，学生查找效率低。

**校园智答** 将这些静态文档收录为知识库，学生用自然语言提问即可获得准确的答案 + 引用来源。

### 1.2 功能列表

| 编号 | 功能 | 说明 | 优先级 | 状态 |
|------|------|------|--------|:--:|
| F1 | 文档上传 | 支持 TXT/MD/PDF，自动提取文本 | P0 | ✅ |
| F2 | 自动分块 | 多策略自适应（Markdown / Q&A / 中文结构 / 滑动窗口） | P0 | ✅ |
| F3 | 向量化 | DashScope text-embedding-v3，1024维，自动分批≤10 | P0 | ✅ |
| F4 | 语义检索 | 余弦相似度 + 关键词加权重排序 | P0 | ✅ |
| F5 | RAG问答 | 检索上下文 + Prompt模板 + LLM生成 | P0 | ✅ |
| F6 | 多轮对话 | Redis缓存对话历史，24h TTL | P0 | ✅ |
| F7 | 文档管理 | 列表查询、状态查看 | P1 | ✅ |
| F8 | 对话历史 | 按会话查询历史记录 | P1 | ✅ |
| F9 | PDF解析 | 集成PDFBox提取文本 | P1 | ✅ |
| F10 | DOCX解析 | 集成POI提取文本 | P1 | ⬜ |
| F11 | 前端页面 | 简易对话+上传界面 | P2 | ⬜ |

### 1.3 文档分类设计

| 分类 | 编码 | 示例文档 |
|------|------|---------|
| 规章制度 | POLICY | 学生手册、宿舍管理条例、图书馆借阅规则 |
| 评奖评优 | SCHOLARSHIP | 国家奖学金评定办法、三好学生评选细则 |
| 学业政策 | ACADEMIC | 保研推免办法、转专业规定、辅修管理办法 |
| 生活指南 | GUIDE | 校园卡使用指南、新生入学指南、Q&A格式FAQ |
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
├── pom.xml                                  # Maven配置（Spring Boot 3.2 + PDFBox）
├── docker-compose.yml                       # Docker环境（MySQL + Redis + RabbitMQ）
├── DEVELOPMENT_PLAN.md                      # 本文件 — 开发计划
├── README.md                                # 项目说明（待完成）
│
├── src/main/resources/
│   ├── application.yaml                     # 全部配置（MySQL/Redis/RabbitMQ/LLM/RAG参数）
│   └── db/
│       └── init.sql                         # 建库建表 + 测试数据
│
└── src/main/java/com/rag/campus/
    │
    ├── RagCampusApplication.java            # 启动类
    │
    ├── common/
    │   └── Result.java                      # 统一响应格式
    │
    ├── config/
    │   ├── AppConfig.java                   # RestTemplate Bean
    │   └── RabbitMQConfig.java              # Exchange/Queue/Binding 声明
    │
    ├── entity/
    │   ├── Document.java                    # 文档表
    │   ├── DocumentChunk.java               # 分块表
    │   └── Conversation.java                # 对话表
    │
    ├── mapper/
    │   ├── DocumentMapper.java
    │   ├── DocumentChunkMapper.java
    │   └── ConversationMapper.java
    │
    ├── dto/
    │   ├── ChatRequest.java
    │   ├── ChatResponse.java
    │   └── DocumentUploadResult.java
    │
    ├── client/
    │   ├── DeepSeekClient.java              # LLM对话（temperature=0.3，支持多轮）
    │   └── EmbeddingClient.java             # DashScope向量化（自动分批≤10条）
    │
    ├── support/                              # 核心支撑组件
    │   ├── DocumentConverter.java           # 文档转换器接口（策略模式）
    │   ├── DocumentChunker.java             # 多策略分块器
    │   ├── VectorStore.java                 # 内存向量索引（余弦相似度）
    │   ├── RagPromptTemplate.java           # RAG Prompt模板
    │   └── impl/
    │       ├── PdfBoxConverter.java         # PDF → 文本
    │       ├── PlainTextConverter.java      # TXT/MD → 文本
    │       └── MarkItDownConverter.java     # 预留扩展：统一转Markdown
    │
    ├── service/
    │   ├── DocumentService.java
    │   ├── RagService.java
    │   └── impl/
    │       ├── DocumentServiceImpl.java      # 上传 + MQ异步处理
    │       ├── RagServiceImpl.java           # RAG链路 + 关键词加权 + 文档缓存
    │       └── DocumentProcessConsumer.java  # RabbitMQ消费者
    │
    └── controller/
        ├── DocumentController.java           # /api/documents/*
        └── ChatController.java               # /api/chat/*
```

### 分层依赖关系

```
Controller → Service → Mapper → Entity
                ↓
            Support (DocumentConverter, DocumentChunker, VectorStore, PromptTemplate)
                ↓
            Client (DeepSeekClient, EmbeddingClient)
```

- **Controller** 只做参数校验和结果封装
- **Service** 编排业务流程，调用 Support + Client + Mapper
- **Support** 核心逻辑组件，DocumentConverter/DocumentChunker 均采用策略模式便于扩展
- **Client** 封装外部HTTP API调用

---

## 三、技术全景

### 3.1 本地基础设施

| 工具 | 版本 | 用途 | 方式 |
|------|------|------|------|
| JDK | 17+ | Spring Boot 3.x 最低要求 | 本地安装 |
| MySQL | 8.0 | 文档/分块/对话记录持久化 | Docker |
| Redis | 7.x | 对话历史缓存 | Docker |
| RabbitMQ | 3.12 | 文档异步处理消息队列 | Docker |
| Maven | 3.8+ | 依赖管理 | 本地安装 |

### 3.2 远程API服务

| 服务 | 地址 | 费用 | 用途 |
|------|------|------|------|
| DeepSeek | platform.deepseek.com | 充值10元 | LLM对话生成 |
| 阿里云DashScope | dashscope.aliyun.com | 免费50万token | 文本向量化（text-embedding-v3） |

### 3.3 Java依赖清单

| 依赖 | 版本 | 作用 |
|------|------|------|
| spring-boot-starter-web | 3.2.0 | REST接口 |
| spring-boot-starter-data-redis | 3.2.0 | Redis集成 |
| spring-boot-starter-amqp | 3.2.0 | RabbitMQ集成 |
| mybatis-plus-spring-boot3-starter | 3.5.5 | ORM |
| mysql-connector-j | SB管理 | MySQL驱动 |
| commons-pool2 | SB管理 | Redis连接池 |
| hutool-all | 5.8.25 | JSON/字符串工具 |
| lombok | SB管理 | 消除样板代码 |
| pdfbox | 2.0.30 | PDF文本提取 |

---

## 四、核心架构

### 4.1 系统架构图

```
┌─────────────────────────────────────────────────────────────┐
│                     前端（Postman / HTML）                    │
└──────────────┬──────────────────────────────┬───────────────┘
               │ 上传文档                       │ 提问
               ▼                               ▼
┌──────────────────────┐         ┌───────────────────────────┐
│  DocumentController  │         │     ChatController        │
│  POST /upload        │         │     POST /ask             │
└──────────┬───────────┘         └──────────┬────────────────┘
           │                                │
           ▼                                ▼
┌──────────────────────┐         ┌───────────────────────────┐
│  DocumentServiceImpl │         │     RagServiceImpl         │
│  ① 提取文本(Converter)│        │  ① 问题向量化              │
│  ② 存DB (PENDING)    │         │  ② VectorStore.search()   │
│  ③ 发MQ消息          │         │  ③ 关键词加权重排序        │
└──────────┬───────────┘         │  ④ 加载对话历史(Redis)     │
           │                     │  ⑤ Prompt组装              │
           ▼                     │  ⑥ DeepSeek.chat()        │
┌──────────────────────┐         │  ⑦ 保存记录(MySQL+Redis)   │
│     RabbitMQ          │         └──────────┬────────────────┘
│  document.process     │                    │
└──────────┬───────────┘         ┌──────────▼────────────────┐
           │                     │  DeepSeek API (cloud)     │
           ▼                     │  DashScope API (cloud)    │
┌──────────────────────┐         └───────────────────────────┘
│ DocumentProcessConsumer│
│  → processDocument() │
│  ① DocumentChunker   │
│  ② EmbeddingClient   │
│  ③ 写DB + VectorStore│
│  ④ 清空content字段   │
└──────────────────────┘
           │
           ▼
┌──────────────────────┐    ┌───────────────────────────┐
│       MySQL           │    │         Redis              │
│  tb_document (元数据)  │    │  rag:conversation:{sid}   │
│  tb_document_chunk    │    │  (对话历史, 24h TTL)       │
│  tb_conversation      │    │                            │
└───────────────────────┘    └───────────────────────────┘
```

### 4.2 文档入库链路（异步）

```
用户上传文件
  → DocumentServiceImpl.upload()
    → DocumentConverter.convert(bytes, filename)
        ├─ .txt/.md → PlainTextConverter (UTF-8)
        └─ .pdf     → PdfBoxConverter (PDFBox)
    → INSERT tb_document (status=PENDING)
    → rabbitTemplate.convertAndSend(documentId)
    → 立即返回 "处理中"

        ═══════ 以下是异步执行 ═══════

RabbitMQ → DocumentProcessConsumer.handleDocumentProcess(documentId)
  → DocumentServiceImpl.processDocument(documentId)
    → UPDATE status=PROCESSING
    → DocumentChunker.chunk(text, category)
       策略选择（按优先级）：
         1. 检测到 # / ## / ### → Markdown 标题切分
         2. 检测到 Q1/Q2/Q3...  → Q&A 格式切分（自动跳过目录页）
         3. POLICY/SCHOLARSHIP/ACADEMIC + "第X章" → 中文结构切分
         4. 兜底 → 滑动窗口（800字/块, 200字重叠）
       → List<String> chunks
    → EmbeddingClient.embedBatch(chunks)
       DashScope 单次 ≤10 条，超限自动分批 + 200ms延迟
       → List<float[]>
    → 批量 INSERT tb_document_chunk (文本 + JSON向量)
    → VectorStore.addBatch() 加载到内存
    → doc.setContent("")  清空原文，避免大字段撑大表
    → UPDATE status=DONE, chunk_count=N
```

### 4.3 RAG问答链路（同步）

```
用户提问 "国家奖学金评选条件是什么？"
  → RagServiceImpl.ask()

  Step 1 — 向量化
    EmbeddingClient.embed(question) → float[1024]

  Step 2 — 宽窗口语义检索
    VectorStore.search(queryVector, topK×3)
      → 遍历所有chunk向量，计算余弦相似度
      → cos(θ) = (A·B) / (|A| × |B|)
      → 返回 24 个候选chunk

  Step 3 — 关键词加权重排序
    rerankByKeywordBoost(question, candidates, topK=8)
      → 提取查询中的关键词（如"考试""违纪""认定""处理"）
      → 每个候选chunk: boostedScore = vectorScore + 0.2 × keywordMatchRatio
      → 按boostedScore降序，取 Top-8

  Step 4 — 加载对话历史
    Redis GET rag:conversation:{sessionId}
      → List<Map<role, content>> 最近10轮

  Step 5 — Prompt组装
    System: "你是校园知识库助手，仅根据参考资料回答..."
    User:   "参考资料：{Top-8 chunk文本}
             用户问题：国家奖学金评选条件是什么？"

  Step 6 — LLM生成
    DeepSeekClient.chatWithHistory(messages) → answer

  Step 7 — 持久化
    INSERT tb_conversation (session_id, question, answer, sources)
    Redis SET rag:conversation:{sessionId} (追加本轮, 重置24h TTL)

  Step 8 — 返回
    { answer: "...", sources: [{title, chunkIndex, score, snippet}], sessionId }
```

### 4.4 关键设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 向量存储 | 内存ConcurrentHashMap | Demo数据量可控（<5000 chunk），单条6KB，遍历<50ms |
| 分块策略 | 4级自适应（MD→Q&A→中文结构→滑动窗口） | 不同文档用不同策略，结构优先保语义完整 |
| 文档转换 | DocumentConverter接口（策略模式） | PDFBox当前实现，预留MarkItDown扩展点，加格式不改代码 |
| 检索增强 | 向量检索 + 关键词加权重排序 | 纯向量检索对中文近义但不相关的chunk排序不准 |
| 文档处理 | MQ异步 | Embedding API 3-10s/次，异步避免用户等待 |
| 原文存储 | 处理完即清空content字段 | 避免大文本撑大表空间、拖慢列表查询 |
| 对话历史 | Redis热数据 + MySQL冷归档 | Redis快适合热读，MySQL做持久化；24h TTL自动清理 |
| LLM调用 | RestTemplate直连API | 不引入LangChain4j等框架增加学习成本 |
| Embedding分批 | 单次≤10条，自动拆分+延迟 | DashScope API硬限制 |

---

## 五、数据库设计

### 5.1 ER图

```
tb_document                         tb_conversation
┌──────────────────────┐           ┌──────────────────────┐
│ id (PK)              │           │ id (PK)              │
│ title                │           │ session_id           │
│ category             │           │ question             │
│ department           │           │ answer               │
│ content (LONGTEXT)   │           │ sources (JSON)       │
│ file_type            │           │ create_time          │
│ status               │           └──────────────────────┘
│ chunk_count          │
│ create_time          │
│ update_time          │
└──────┬───────────────┘
       │ 1
       │ N
┌──────▼───────────────┐
│ tb_document_chunk    │
│ id (PK)              │
│ document_id (FK)     │
│ chunk_index          │
│ chunk_text (TEXT)    │  ← 800字/块的中文文本
│ embedding (LONGTEXT) │  ← JSON格式 float[1024]
│ create_time          │
└──────────────────────┘
```

### 5.2 设计说明

- `content` 字段仅在处理期间暂存，处理完成后清空（设为空字符串）。原文的实际内容在 `tb_document_chunk` 中按序存储
- 如需查看原文：按 `document_id + chunk_index` 排序拼接 `chunk_text` 即可还原
- 列表查询 `GET /api/documents` 不加载大字段，性能不受文档数量影响

### 5.3 Redis Key设计

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `rag:conversation:{sessionId}` | String(JSON) | 24h | 对话历史，格式: [{role, content}, ...] |

---

## 六、API接口设计

### 6.1 文档管理

#### POST /api/documents/upload

```
Content-Type: multipart/form-data

参数:
  file:       (必填) .txt / .md / .pdf
  title:      (选填) 文档标题
  category:   (必填) POLICY / SCHOLARSHIP / ACADEMIC / GUIDE / OTHER
  department: (选填) 发布单位

响应:
{
  "success": true,
  "data": {
    "documentId": 1,
    "title": "本科生奖学金评定办法",
    "status": "PENDING",
    "message": "文档上传成功，正在后台处理中，请稍候..."
  }
}
```

#### GET /api/documents — 列表

#### GET /api/documents/{id} — 详情

### 6.2 智能问答

#### POST /api/chat/ask

```
请求:
{
  "sessionId": "a1b2c3d4",    // 首次为空，后续传入实现多轮对话
  "question": "国家奖学金评选条件是什么？"
}

响应:
{
  "success": true,
  "data": {
    "sessionId": "a1b2c3d4",
    "answer": "根据...",
    "sources": [
      { "title": "...", "chunkIndex": 2, "score": 0.93, "snippet": "..." }
    ]
  }
}
```

#### GET /api/chat/history/{sessionId} — 对话历史

---

## 七、开发进度（实际 vs 计划）

### 已完成 ✅

| 日期 | 内容 |
|------|------|
| 7/6（提前） | Docker环境搭建（docker-compose.yml：MySQL 8.0 + Redis 7 + RabbitMQ 3.12） |
| 7/6（提前） | DeepSeek + DashScope API Key配置 + 调通 |
| 7/6（提前） | PDFBox集成，PDF上传+文本提取 |
| 7/6（提前） | DocumentConverter接口化（PdfBoxConverter + PlainTextConverter + MarkItDown预留） |
| 7/6（提前） | 多策略分块器：Markdown / Q&A / 中文结构 / 滑动窗口，800字/块 |
| 7/6（提前） | Embedding自动分批（≤10条），批次间200ms延迟 |
| 7/6（提前） | 关键词加权重排序（向量分 + 关键词命中） |
| 7/6（提前） | RAG问答全链路验证通过，检索召回准确 |
| 7/6（提前） | 多轮对话：Redis缓存历史 + Django上下文传递 |
| 7/6（提前） | content字段处理完清空，避免大字段问题 |
| 7/6（提前） | 文档查询缓存（16次DB → 1次） |
| 7/6（提前） | 简历bullet points + 面试Q&A准备 |

### 待完成 ⬜

| 优先级 | 任务 |
|--------|------|
| P1 | DOCX文件支持（Apache POI） |
| P2 | HTML前端对话页面 |
| P2 | README.md |
| P2 | 服务重启时PROCESSING状态补偿 |
| P3 | Embedding重试机制（3次指数退避） |
| P3 | 检索低分拦截（score < 0.5 不调LLM） |

---

## 八、面试准备指南

### 8.1 项目一句话介绍

> 校园智答是一个基于RAG架构的校园知识库问答系统。它把学校规章制度、评奖评优政策、学业指南等静态文档进行分块和向量化后存入知识库，学生用自然语言提问时，系统通过语义检索找到相关内容，再由大模型生成带来源引用的准确回答。
>
> 技术栈用的是 Spring Boot 3.2 + MyBatis-Plus + Redis + RabbitMQ，LLM对接 DeepSeek，向量化用阿里云 DashScope。

### 8.2 高频面试问题及回答要点

#### Q1：什么是RAG？你这个项目里RAG是怎么实现的？

- RAG = Retrieval-Augmented Generation（检索增强生成）
- 解决的问题：大模型训练数据有截止日期，且容易"幻觉"编造
- 实现链路：用户问题 → Embedding向量化 → 宽窗口检索（topK×3）→ 关键词加权重排序 → 拼入Prompt → LLM生成 → 标注来源
- 关键设计：System Prompt约束"仅根据参考资料回答"

#### Q2：文档是怎么处理的？分块策略是什么？

- 上传后立即返回，MQ异步处理（Embedding API一次调用3-10秒）
- **多策略自适应分块**：
  1. Markdown标题切分（`#`/`##`/`###`）→ 适用.md文件
  2. Q&A格式切分（`Q1`/`Q2`等）→ 适用新生指南等FAQ文档，自动跳过目录
  3. 中文结构切分（`第X章`/`第X条`）→ 适用政策文档，过滤总结句假阳性
  4. 滑动窗口兜底（800字/块，200字重叠）→ 适用自由文本
- 向量化：DashScope text-embedding-v3 → 1024维，自动分批≤10条
- 存储：MySQL存文本+JSON向量做持久化，内存VectorStore做热检索
- 处理完成后清空原文content字段，避免大字段拖慢列表查询

#### Q3：向量检索怎么做的？为什么不用专业向量数据库？

- 目前用内存ConcurrentHashMap + 余弦相似度遍历
- Demo阶段数据量小（<5000 chunk），单条6KB，遍历<50ms，够用
- 设计上VectorStore可替换——抽象出接口后换Redis Stack RediSearch/Milvus不改业务代码
- `@PostConstruct` 启动时从DB加载所有向量到内存
- 面试加分：主动说清楚拐点在哪（万级以上考虑换），以及怎么换

#### Q4：消息队列在项目里起了什么作用？

- 文档上传后不阻塞用户，异步处理分块+向量化
- 削峰：如果同时上传多份文档，MQ排队处理
- 解耦：上传服务和文档处理服务不直接依赖
- 和黑马点评秒杀异步下单一个思路（展示技术连贯性）

#### Q5：你怎么保证回答的准确性？如何防止大模型幻觉？

- System Prompt 强约束："仅根据参考资料回答，不知道就说不知道"
- **关键词加权重排序**：向量相似度 + 关键词匹配度双重排序，防止"语义相近但不相关"的chunk排在前面
- 每次回答标注引用来源（文档标题 + chunk序号 + 相似度得分）
- 低temperature参数（0.3）降低模型随机性

#### Q6：如果知识库里没有相关内容，用户提问会怎样？

- Prompt里已约束："如果参考资料中没有相关信息，告知用户暂无收录"
- 检索结果score都低时也可以提前拦截，不调LLM直接返回

#### Q7：你的分块策略是怎么设计的？为什么不用固定大小？

- 不同文档有不同结构：政策文档有"第X章"，新生指南有Q1/Q2/Q3，自由文本没有固定结构
- 一刀切的固定大小会：把完整条文拦腰截断、目录页被当成正文、总结句被当成标题
- 所以做了4级自适应：MD标题 → Q&A → 中文结构 → 滑动窗口兜底
- DocumentConverter + DocumentChunker 都是策略模式，加新格式不改旧代码

#### Q8：如果文档有300页，你的系统能处理吗？

- 能。分块后的chunk存在MySQL中，检索时只在内存中计算向量相似度（float数组），不加载原文
- 300页≈15万字≈200个chunk≈1.2MB内存，完全没问题
- 原文content字段处理完就清空，不会拖慢数据库
- 真正需要关注的是Embedding API调用耗时（200个chunk=20批≈30-60秒），但对用户是异步的，无感知

### 8.3 简历Bullet Points

```
校园智答 — 基于RAG的校园知识库问答系统
Spring Boot 3.2 + Redis + RabbitMQ + DeepSeek + DashScope | 2026.07

• 设计并实现RAG架构的校园知识库问答系统，覆盖规章制度、评奖评优、
  学业政策、生活指南等多类文档，支持自然语言问答及多轮对话

• 实现多策略自适应文档分块（Markdown标题 / Q&A格式 / 中文结构 /
  滑动窗口），按文档分类+内容特征自动选择最优策略，保证语义完整性

• 设计关键词加权重排序算法（向量相似度 + 关键词匹配度），
  配合DashScope Embedding + 余弦相似度实现高质量语义检索

• 使用RabbitMQ异步处理文档向量化流程，Redis缓存对话历史(24h TTL)
  实现多轮上下文理解；通过DocumentConverter策略模式预留MarkItDown扩展点

• 设计Prompt模板约束LLM行为（仅依据参考资料回答+标注来源），
  有效缓解大模型幻觉问题，提升回答准确性及可信度
```

---

## 九、环境搭建清单

### 9.1 Docker环境（推荐）

```bash
# 启动全部服务（MySQL 8.0 + Redis 7 + RabbitMQ 3.12）
docker compose up -d

# 数据库自动初始化（init.sql挂载到容器启动脚本）
# 无需手动执行SQL

# 验证
docker compose ps
# 三个容器均为 healthy 即就绪
```

### 9.2 API Key 注册

| 序号 | 操作 | 地址 |
|------|------|------|
| 1 | 注册DeepSeek，充值10元，创建API Key | platform.deepseek.com |
| 2 | 注册阿里云，开通DashScope（百炼），创建API Key | bailian.console.aliyun.com |

### 9.3 项目启动步骤

```bash
# 1. 启动Docker服务
docker compose up -d

# 2. 配置 application.yaml 中的 API Key
# deepseek.api-key: sk-xxxxxx
# embedding.dashscope.api-key: sk-xxxxxx

# 3. IDEA打开项目，运行 RagCampusApplication

# 4. 测试（PowerShell）
Invoke-RestMethod -Uri http://localhost:8081/api/documents

# 5. 上传文档（Postman）
# POST http://localhost:8081/api/documents/upload
# form-data: file + category
```

---

## 十、分块策略详解

### 10.1 策略选择流程

```
DocumentChunker.chunk(text, category)
  │
  ├─ 优先级1: 检测到 # / ## / ### 标题？
  │   └─ YES → chunkByMarkdown(text)
  │       按标题层级切分，每个标题+内容 = 一个chunk
  │       e.g. ## Q1 如何查录取结果？ + 回答 → chunk 0
  │
  ├─ 优先级2: 检测到 Q1/Q2/Q3... 格式？
  │   └─ YES → chunkByQA(text)
  │       按Q标记切分，自动跳过目录页（连续短section < 80字）
  │       e.g. Q1 ...(300字)→ chunk 0, Q2 ...(500字)→ chunk 1
  │
  ├─ 优先级3: 分类为 POLICY/SCHOLARSHIP/ACADEMIC 且含 "第X章"？
  │   └─ YES → chunkByStructure(text)
  │       按章节边界切分，过滤总结句假阳性（行长>60或含"介绍/说明"）
  │       e.g. 第一章 总则 → chunk 0, 第二章 ... → chunk 1
  │
  └─ 优先级4: 兜底 → chunkBySlidingWindow(text)
      段落优先 + 滑动窗口（800字/块, 200字重叠）
```

### 10.2 参数配置

```yaml
rag:
  chunk:
    size: 800          # chunk最大字符数
    overlap: 200       # 重叠字符数（size的25%）
  retrieval:
    top-k: 8           # 最终返回chunk数（宽窗口 topK×3 召回后重排序截断）
```

---

## 十一、已解决的问题清单

| # | 问题 | 解决方案 |
|---|------|---------|
| 1 | 日期/星期全部写错 | 7/8周三→7/12周日，全量修正 |
| 2 | PDF提取时二进制被当UTF-8读成乱码 | PDF/DOCX直接抛异常，不读bytes |
| 3 | MyBatis-Plus selectById(Serializable)无法匹配Function<Long, Document> | 桥接方法getDocumentById(Long) + this::方法引用 |
| 4 | DashScope单次最多10条，15个chunk报400 | embedBatch自动拆分，10条/批+200ms延迟 |
| 5 | 检索召回不准确（"考试违纪"排在监考人员后面） | chunk 512→800，结构优先切分，关键词加权重排序 |
| 6 | 目录页Q1/Q2被当成正文切分 | Q&A切分自动跳过连续短section（<80字） |
| 7 | 总结段落"第一章介绍了..."被误匹配 | 中文结构切分行长>60或含"介绍/说明"过滤 |
| 8 | 16次重复查同一文档 | HashMap做请求级缓存，降至1次 |
| 9 | content大字段影响列表查询性能 | 处理完成后清空content，原文从chunk拼接还原 |

---

> **最后更新：2026/07/06**
>
> **当前状态：** 核心链路已完成，进入打磨阶段。待完成：DOCX支持、HTML前端、README。
