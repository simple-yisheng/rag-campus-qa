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
上传:  MD5去重 → DocumentConverter(策略模式) → MySQL(PENDING) + MinIO(原始文件) → RabbitMQ → 异步处理
异步:  DocumentChunker(4级自适应) → EmbeddingClient(≤10分批) → MySQL + VectorStore(内存)
问答:  查询改写(多轮上下文) → Embedding → VectorStore.search(宽窗口) → 关键词加权重排 → DeepSeek生成 → MySQL+Redis持久化(仅存Q&A)
```

### MinIO 对象存储

- 原始文件存储: `MinioStorageService` — 上传时自动存入 `documents/{docId}/{filename}`
- 下载接口: `GET /api/documents/{id}/file` — PDF 内联预览，其他格式触发下载（【已知问题】使用自增 ID 可被遍历访问，待改为随机 token）
- 管理控制台: `http://localhost:9001`（minioadmin/minioadmin）
- MinIO 故障不影响检索流程（upload 中 try-catch 容错）
- Docker 数据位置: `D:\Docker\DockerDesktopWSL\`

## 分块策略（4级自适应优先级）

1. Markdown 标题切分 — 检测 `#`/`##`/`###` → chunkByMarkdown()
2. Q&A 格式切分 — 检测 `Q1`/`Q2` 等 → chunkByQA()（自动跳过目录页）
3. 中文结构切分 — POLICY/SCHOLARSHIP/ACADEMIC + "第X章" → chunkByStructure()（过滤总结句）
4. 滑动窗口兜底 — 800字/块, 200字重叠

## 关键实现细节

- **EmbeddingClient**: DashScope 兼容模式 API，单次最多10条，超限自动分批 + 200ms 延迟
- **VectorStore**: ConcurrentHashMap 内存索引，启动时 @PostConstruct 从 DB 加载，余弦相似度遍历
- **检索增强**: 宽窗口召回(topK×3) → 关键词加权(向量分 + 0.2×命中率) → 截断 topK=8
- **DocumentConverter**: 策略模式，PdfBoxConverter(.pdf) + PlainTextConverter(.txt/.md) + DocxConverter(.docx/.doc，表格→Markdown表格)，预留 MarkItDownConverter
- **DocxConverter**: 遍历 XWPFDocument.bodyElements，段落保留原文，表格格式化为 Markdown 表格（管道符+分隔线），避免表格结构丢失导致分块截断
- **MD5 去重**: 上传时计算文件字节 MD5，查询 content_hash 列，已存在且 status=DONE 则拦截返回
- **MinIO 存储**: 上传时自动存入原始文件，`GET /api/documents/{id}/file` 下载预览，MinIO 故障不影响检索
- **SourceInfo 增强**: 返回 documentId，前端可据此请求原始文件实现"点击参考资料查看原文"
- **文档缓存**: RagServiceImpl 请求级 HashMap，避免 buildUserPrompt + buildSources 重复查 DB（16次→1次）
- **content 字段**: 处理完成后清空，原文从 tb_document_chunk 拼接还原，避免大字段拖慢列表查询
- **多轮对话**: Redis `rag:conversation:{sessionId}` 存储瘦身 Q&A（仅原始问答，去 system prompt 和 chunk），24h TTL；查询改写：追问先结合上文经 LLM 改写为独立完整查询，再向量化检索
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
| 2026-07-08 | 表格分块修复 — ① chunkBySlidingWindow 表格感知：跨 chunk 自动补表头+分隔线；② PdfBoxConverter 表格检测：坐标分析→Markdown表格输出 |
| 2026-07-07 | DOCX/DOC 文件支持（Apache POI，表格→Markdown表格保留结构） |
| 2026-07-07 | MinIO 对象存储（原始文件保存+下载预览） |
| 2026-07-07 | MD5 去重（相同文件拦截重复上传） |
| 2026-07-07 | SourceInfo 增加 documentId（前端可点击查看原文） |
| 2026-07-07 | Docker 数据迁移至 D 盘 |
| 2026-07-07 | Vue 3 前端（TDesign Chat，对话页+文档管理页） |

## 待完成

| 优先级 | 任务 | 说明 |
|--------|------|------|
| **P1** | **参考资料定位** | 点击参考资料 → 侧边抽屉展示文档完整纯文本 + 高亮定位到对应 chunk；新增 `GET /api/documents/{id}/content` |
| P2 | 对话标题重命名 | 左侧会话列表支持双击/右键重命名 |
| P2 | sessionId 缩短 | 当前 UUID 偏长，改用短 ID（如 6 位字母数字） |
| P2 | 文件访问 URL 安全 | `GET /api/documents/{id}/file` 改为使用随机 token，避免用户遍历 ID 访问他人文件 |
| P2 | README.md | 项目说明文档 |
| P2 | 服务重启时 PROCESSING 状态补偿 | 扫描 status=PROCESSING 重新投 MQ |
| P3 | Embedding 重试（3次指数退避） | |
| P3 | 检索低分拦截（score < 0.5 不调 LLM） | |
| P3 | VectorStore 接口化（为 Redis Stack / Milvus 替换做准备） | |

## 面试要点

项目是黑马点评的技术延续：Redis 缓存、RabbitMQ 异步、MyBatis-Plus CRUD 都是同一个技术栈。面试时强调：多策略分块的设计思路、关键词加权的改进动机（纯向量检索的语义偏差问题）、DocumentConverter 的扩展性预留。
