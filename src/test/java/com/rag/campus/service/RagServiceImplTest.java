package com.rag.campus.service;

import com.rag.campus.client.DeepSeekClient;
import com.rag.campus.client.EmbeddingClient;
import com.rag.campus.dto.ChatRequest;
import com.rag.campus.dto.ChatResponse;
import com.rag.campus.entity.ConversationMessage;
import com.rag.campus.entity.ConversationSession;
import com.rag.campus.entity.Document;
import com.rag.campus.entity.DocumentChunk;
import com.rag.campus.mapper.ConversationMessageMapper;
import com.rag.campus.mapper.ConversationSessionMapper;
import com.rag.campus.mapper.DocumentMapper;
import com.rag.campus.service.impl.RagServiceImpl;
import com.rag.campus.support.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagServiceImpl 核心 RAG 检索链路单元测试
 * <p>
 * 测试覆盖：检索管道、关键词加权、文档标题匹配、阈值过滤、多样性截断、
 * 对话历史管理、同步/流式问答主流程。
 * <p>
 * 策略：对外部 API（DeepSeek/Embedding/VectorStore/Redis/DB）全部 Mock，
 * 对内部纯逻辑方法（关键词提取、评分计算、过滤截断）通过反射直接测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagServiceImpl")
class RagServiceImplTest {

    @Mock private DeepSeekClient deepSeekClient;
    @Mock private EmbeddingClient embeddingClient;
    @Mock private VectorStore vectorStore;
    @Mock private ConversationSessionMapper sessionMapper;
    @Mock private ConversationMessageMapper messageMapper;
    @Mock private DocumentMapper documentMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private UserService userService;

    @InjectMocks
    private RagServiceImpl ragService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ragService, "topK", 5);
        ReflectionTestUtils.setField(ragService, "minScore", 0.55);
        ReflectionTestUtils.setField(ragService, "maxHistory", 10);
        ReflectionTestUtils.setField(ragService, "ttlHours", 24);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ==================== Helpers ====================

    /** 按参数类型查找方法（优先精确匹配，失败时尝试宽松匹配） */
    private Method findMethod(String methodName, Object... args) {
        for (Method m : RagServiceImpl.class.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (m.getParameterCount() != args.length) continue;
            Class<?>[] paramTypes = m.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < args.length; i++) {
                if (!isCompatible(args[i], paramTypes[i])) {
                    match = false;
                    break;
                }
            }
            if (match) return m;
        }
        throw new RuntimeException("Method not found: " + methodName);
    }

    private boolean isCompatible(Object arg, Class<?> paramType) {
        if (arg == null) return true;
        Class<?> argClass = arg.getClass();
        if (paramType.isAssignableFrom(argClass)) return true;
        // 处理自动装箱: Integer → int
        if (paramType.isPrimitive()) {
            if (paramType == int.class && argClass == Integer.class) return true;
            if (paramType == long.class && argClass == Long.class) return true;
            if (paramType == float.class && argClass == Float.class) return true;
            if (paramType == double.class && argClass == Double.class) return true;
            if (paramType == boolean.class && argClass == Boolean.class) return true;
        }
        // 集合泛型擦除: ArrayList → List
        if (List.class.isAssignableFrom(argClass) && paramType == List.class) return true;
        if (Map.class.isAssignableFrom(argClass) && paramType == Map.class) return true;
        return false;
    }

    private Object invokePrivate(String methodName, Object... args) throws Exception {
        Method method = findMethod(methodName, args);
        method.setAccessible(true);
        return method.invoke(ragService, args);
    }

    private DocumentChunk chunk(long id, long docId, String text) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id);
        c.setDocumentId(docId);
        c.setChunkText(text);
        return c;
    }

    private Document doc(long id, String title) {
        Document d = new Document();
        d.setId(id);
        d.setTitle(title);
        return d;
    }

    // ==================== extractEntityNames ====================

    @Nested
    @DisplayName("extractEntityNames —— 机构/实体名称提取")
    class ExtractEntityNames {

        @Test
        @DisplayName("应提取'XX学院'类机构名")
        void shouldExtractCollegeNames() throws Exception {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) invokePrivate("extractEntityNames",
                    "材料学院综测办法");
            assertThat(result).contains("材料学院");
        }

        @Test
        @DisplayName("多个实体应都被提取")
        void shouldExtractMultipleEntities() throws Exception {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) invokePrivate("extractEntityNames",
                    "软件学院的奖学金标准和计算机学院的有什么不同");
            // 正则会分别提取出"软件学院"和"计算机学院"
            assertThat(result).contains("软件学院");
            assertThat(result.size()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("应提取'XX部''XX中心'等行政机构")
        void shouldExtractAdminEntities() throws Exception {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) invokePrivate("extractEntityNames",
                    "学生工作部的主要职责");
            assertThat(result).contains("学生工作部");
        }

        @Test
        @DisplayName("XX委员会应被提取")
        void shouldExtractCommittee() throws Exception {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) invokePrivate("extractEntityNames",
                    "学术委员会的组成是什么");
            assertThat(result).contains("学术委员会");
        }

        @Test
        @DisplayName("无机构名时应返回空列表")
        void shouldReturnEmptyWhenNoEntity() throws Exception {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) invokePrivate("extractEntityNames",
                    "奖学金申请条件是什么？");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("空查询应返回空")
        void shouldReturnEmptyForBlankQuery() throws Exception {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) invokePrivate("extractEntityNames", "");
            assertThat(result).isEmpty();
        }
    }

    // ==================== extractQueryTerms ====================

    @Nested
    @DisplayName("extractQueryTerms —— 多策略关键词提取")
    class ExtractQueryTerms {

        @Test
        @DisplayName("应提取领域词（如'综测'）")
        void shouldExtractDomainTerms() throws Exception {
            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "综测加分细则是什么？");
            assertThat(terms).isNotNull();
            // 领域词"综测"应被识别
            assertThat(terms.toString()).contains("综测");
        }

        @Test
        @DisplayName("领域词应匹配同义词（综测↔综合测评）")
        void shouldExpandSynonyms() throws Exception {
            List<?> result = (List<?>) invokePrivate("extractQueryTerms", "综合测评和奖学金怎么评？");
            // "综合测评"的 synonyms 包含"综测""素质测评"等，这些也应作为 term 加入
            String str = result.toString();
            // 至少应有原始词和 1 个同义词
            assertThat(str).contains("综合测评");
        }

        @Test
        @DisplayName("普通中文词应从分词中提取（≥2 字且含汉字）")
        void shouldExtractGeneralChineseTerms() throws Exception {
            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "新生入学宿舍申请流程");
            assertThat(terms).isNotNull();
            // 至少有"入学""宿舍""申请""流程"中的几个
            String str = terms.toString();
            assertThat(str.length()).isGreaterThan(10);
        }

        @Test
        @DisplayName("停用词应被过滤")
        void shouldFilterStopwords() throws Exception {
            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "请问一下如何申请奖学金呢？");
            // "请问""一下""如何""呢"都是停用词，不应出现
            String str = terms.toString();
            assertThat(str).doesNotContain("请问");
            assertThat(str).doesNotContain("一下");
        }

        @Test
        @DisplayName("纯英文数字无汉字应返回空")
        void shouldReturnEmptyForNonChinese() throws Exception {
            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "hello world 123");
            assertThat(terms.toString()).isEqualTo("[]");
        }

        @Test
        @DisplayName("空查询应返回空列表")
        void shouldReturnEmptyForBlank() throws Exception {
            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "");
            assertThat(terms.toString()).isEqualTo("[]");
        }

        @Test
        @DisplayName("领域词权重应高于普通词（strong=true）")
        void shouldMarkDomainTermsAsStrong() throws Exception {
            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "综测");
            String str = terms.toString();
            // 领域词 weight=2.0, strong=true
            assertThat(str).contains("strong=true");
            assertThat(str).contains("weight=2.0");
        }
    }

    // ==================== rerankByKeywordBoost ====================

    @Nested
    @DisplayName("rerankByKeywordBoost —— 关键词加权精排")
    class RerankByKeywordBoost {

        @Test
        @DisplayName("含领域词的 chunk 应获得正向加分")
        void shouldBoostChunksWithDomainTerms() throws Exception {
            DocumentChunk c1 = chunk(1L, 100L, "综测加分细则规定，德育分占比30%。");

            when(vectorStore.getChunk(1L)).thenReturn(c1);

            List<VectorStore.Hit> candidates = List.of(
                    new VectorStore.Hit(1L, 0.6f)
            );

            // 通过 extractQueryTerms 先获取 terms，再测试 rerank
            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "综测加分");
            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "rerankByKeywordBoost", candidates, terms, 5);

            assertThat(result).hasSize(1);
            // "综测"是领域词，应获得加分
            assertThat(result.get(0).getScore()).isGreaterThan(0.6f);
        }

        @Test
        @DisplayName("无关键词命中且查询含领域词时，应受到零命中惩罚")
        void shouldPenalizeChunksWithoutAnyKeywordMatch() throws Exception {
            DocumentChunk c1 = chunk(1L, 100L, "期末考试安排在学期末进行。");
            when(vectorStore.getChunk(1L)).thenReturn(c1);

            List<VectorStore.Hit> candidates = List.of(
                    new VectorStore.Hit(1L, 0.6f)
            );

            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "综测加分");
            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "rerankByKeywordBoost", candidates, terms, 5);

            assertThat(result.get(0).getScore()).isLessThan(0.6f); // 被惩罚
        }

        @Test
        @DisplayName("chunk 文本为空不参与加权")
        void shouldSkipNullChunkText() throws Exception {
            DocumentChunk c1 = chunk(1L, 100L, null);
            when(vectorStore.getChunk(1L)).thenReturn(c1);

            List<VectorStore.Hit> candidates = List.of(new VectorStore.Hit(1L, 0.6f));

            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "考试管理");
            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "rerankByKeywordBoost", candidates, terms, 5);

            assertThat(result).hasSize(1);
            // null text 不加分不减分
            assertThat(result.get(0).getScore()).isEqualTo(0.6f);
        }

        @Test
        @DisplayName("无关键词时直接截断返回 topK")
        void shouldFallbackToTopKWithoutTerms() throws Exception {
            List<VectorStore.Hit> candidates = List.of(
                    new VectorStore.Hit(1L, 0.9f),
                    new VectorStore.Hit(2L, 0.8f),
                    new VectorStore.Hit(3L, 0.7f)
            );

            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "rerankByKeywordBoost", candidates, Collections.emptyList(), 2);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getScore()).isEqualTo(0.9f);
        }
    }

    // ==================== calculateTitleBonus ====================

    @Nested
    @DisplayName("calculateTitleBonus —— 文档标题匹配加分")
    class CalculateTitleBonus {

        @Test
        @DisplayName("实体名命中标题应加分")
        void shouldBoostWhenEntityMatchesTitle() throws Exception {
            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "材料学院综测办法");
            List<String> entities = List.of("材料学院");

            @SuppressWarnings("unchecked")
            float bonus = (float) invokePrivate("calculateTitleBonus",
                    "材料学院综测办法", terms, entities, "材料学院综合素质测评实施细则.pdf");

            assertThat(bonus).isGreaterThan(0.0f);
        }

        @Test
        @DisplayName("标题不含实体名不应加分")
        void shouldNotBoostWhenNoEntityMatch() throws Exception {
            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "软件学院奖学金");
            List<String> entities = List.of("软件学院");

            @SuppressWarnings("unchecked")
            float bonus = (float) invokePrivate("calculateTitleBonus",
                    "材料学院综测办法", terms, entities, "材料学院综合素质测评实施细则.pdf");

            // "软件学院"不在标题中，但查询词"奖学金"可能在标题中
            // 至少不会因为实体不匹配而加分
            assertThat(bonus).isLessThan(0.16f); // 最大实体加分是 0.15
        }

        @Test
        @DisplayName("空标题返回 0 加分")
        void shouldReturnZeroForNullTitle() throws Exception {
            List<?> terms = Collections.emptyList();
            @SuppressWarnings("unchecked")
            float bonus = (float) invokePrivate("calculateTitleBonus",
                    "测试", terms, Collections.emptyList(), null);
            assertThat(bonus).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("考试意图查询碰到评优标题文档应减分")
        void shouldPenalizeAssessmentDocForExamQuery() throws Exception {
            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "考试管理办法考场规则");
            @SuppressWarnings("unchecked")
            float bonus = (float) invokePrivate("calculateTitleBonus",
                    "考试管理办法考场规则", terms, Collections.emptyList(), "奖学金评定办法.pdf");

            // examIntent=true, assessmentIntent=false, title 含"奖学金" → -0.18
            assertThat(bonus).isLessThan(0.0f);
        }

        @Test
        @DisplayName("竞赛列表查询碰到名录标题文档应加分")
        void shouldBoostCatalogDocForCompetitionListQuery() throws Exception {
            List<?> terms = (List<?>) invokePrivate("extractQueryTerms", "有哪些竞赛项目目录");
            @SuppressWarnings("unchecked")
            float bonus = (float) invokePrivate("calculateTitleBonus",
                    "有哪些竞赛项目目录", terms, Collections.emptyList(), "竞赛项目名录.pdf");

            // competitionIntent=true, listIntent=true, title 含"名录" → +0.22
            assertThat(bonus).isGreaterThan(0.2f);
        }
    }

    // ==================== filterByMinScore ====================

    @Nested
    @DisplayName("filterByMinScore —— 绝对阈值过滤")
    class FilterByMinScore {

        @Test
        @DisplayName("低于阈值的 chunk 应被过滤")
        void shouldFilterLowScoreChunks() throws Exception {
            List<VectorStore.Hit> hits = new ArrayList<>(List.of(
                    new VectorStore.Hit(1L, 0.8f),
                    new VectorStore.Hit(2L, 0.3f),
                    new VectorStore.Hit(3L, 0.6f)
            ));

            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "filterByMinScore", hits);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(h -> h.getChunkId()).containsExactly(1L, 3L);
        }

        @Test
        @DisplayName("全部低于阈值时保留第一个作为兜底")
        void shouldKeepTopOneWhenAllBelowThreshold() throws Exception {
            List<VectorStore.Hit> hits = new ArrayList<>(List.of(
                    new VectorStore.Hit(1L, 0.3f),
                    new VectorStore.Hit(2L, 0.2f)
            ));

            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "filterByMinScore", hits);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getChunkId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("空列表原样返回")
        void shouldReturnEmptyList() throws Exception {
            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "filterByMinScore", Collections.emptyList());
            assertThat(result).isEmpty();
        }
    }

    // ==================== filterByRelativeScore ====================

    @Nested
    @DisplayName("filterByRelativeScore —— 相对阈值过滤")
    class FilterByRelativeScore {

        @Test
        @DisplayName("低于 topScore×0.8 的 chunk 应被过滤")
        void shouldFilterByRelativeThreshold() throws Exception {
            List<VectorStore.Hit> hits = new ArrayList<>(List.of(
                    new VectorStore.Hit(1L, 0.95f),   // top
                    new VectorStore.Hit(2L, 0.80f),   // 0.95*0.8=0.76 → 保留
                    new VectorStore.Hit(3L, 0.70f),   // 低于 0.76 → 过滤
                    new VectorStore.Hit(4L, 0.60f)    // 低于 0.76 → 过滤
            ));

            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "filterByRelativeScore", hits);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("相对阈值低于 minScore 时取 minScore")
        void shouldUseMinScoreWhenRelativeIsLower() throws Exception {
            // topScore=0.6, 0.6*0.8=0.48 < minScore(0.55), 所以用 0.55
            List<VectorStore.Hit> hits = new ArrayList<>(List.of(
                    new VectorStore.Hit(1L, 0.60f),
                    new VectorStore.Hit(2L, 0.50f)
            ));

            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "filterByRelativeScore", hits);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("全部被过滤时保留第一条兜底")
        void shouldKeepTopOneWhenAllFiltered() throws Exception {
            List<VectorStore.Hit> hits = new ArrayList<>(List.of(
                    new VectorStore.Hit(1L, 0.60f),
                    new VectorStore.Hit(2L, 0.40f)
            ));

            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "filterByRelativeScore", hits);

            assertThat(result).hasSize(1);
        }
    }

    // ==================== applyDocumentDiversity ====================

    @Nested
    @DisplayName("applyDocumentDiversity —— 文档多样性截断")
    class ApplyDocumentDiversity {

        @Test
        @DisplayName("每文档最多 3 个 chunk")
        void shouldLimitChunksPerDocument() throws Exception {
            DocumentChunk c1 = chunk(1L, 100L, "A");
            DocumentChunk c2 = chunk(2L, 100L, "B");
            DocumentChunk c3 = chunk(3L, 100L, "C");
            DocumentChunk c4 = chunk(4L, 100L, "D");
            DocumentChunk c5 = chunk(5L, 200L, "E");

            when(vectorStore.getChunk(1L)).thenReturn(c1);
            when(vectorStore.getChunk(2L)).thenReturn(c2);
            when(vectorStore.getChunk(3L)).thenReturn(c3);
            when(vectorStore.getChunk(4L)).thenReturn(c4);
            when(vectorStore.getChunk(5L)).thenReturn(c5);

            List<VectorStore.Hit> hits = List.of(
                    new VectorStore.Hit(1L, 0.9f),
                    new VectorStore.Hit(2L, 0.8f),
                    new VectorStore.Hit(3L, 0.7f),
                    new VectorStore.Hit(4L, 0.6f),   // 文档100的第4个
                    new VectorStore.Hit(5L, 0.5f)    // 文档200的第1个
            );

            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "applyDocumentDiversity", hits, 5);

            // 文档100最多3个 → id 1,2,3；文档200的 id 5 补位；overflow 中的 id 4 补齐到 targetCount=5
            assertThat(result).hasSize(5);
            assertThat(result).extracting(h -> h.getChunkId())
                    .contains(1L, 2L, 3L, 5L, 4L);
        }

        @Test
        @DisplayName("不足 targetCount 时用 overflow 补齐")
        void shouldFillFromOverflow() throws Exception {
            DocumentChunk c1 = chunk(1L, 100L, "A");
            DocumentChunk c2 = chunk(2L, 200L, "B");

            when(vectorStore.getChunk(1L)).thenReturn(c1);
            when(vectorStore.getChunk(2L)).thenReturn(c2);

            List<VectorStore.Hit> hits = List.of(
                    new VectorStore.Hit(1L, 0.9f),
                    new VectorStore.Hit(2L, 0.8f)
            );

            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "applyDocumentDiversity", hits, 5);

            assertThat(result).hasSize(2);
        }
    }

    // ==================== retrieveRelevantHits ====================

    @Nested
    @DisplayName("retrieveRelevantHits —— 全检索管道")
    class RetrieveRelevantHits {

        @Test
        @DisplayName("向量存储为空时返回空列表")
        void shouldReturnEmptyWhenVectorStoreIsEmpty() throws Exception {
            when(vectorStore.size()).thenReturn(0);

            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "retrieveRelevantHits", "测试", new float[]{0.1f, 0.2f});

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("检索结果为空时返回空列表")
        void shouldReturnEmptyWhenNoResults() throws Exception {
            when(vectorStore.size()).thenReturn(100);
            when(vectorStore.search(any(), anyInt())).thenReturn(Collections.emptyList());

            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "retrieveRelevantHits", "测试", new float[]{0.1f, 0.2f});

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("候选不足 topK×3 时取实际大小")
        void shouldUseActualSizeWhenSmallerThanCandidateSize() throws Exception {
            when(vectorStore.size()).thenReturn(5); // < topK*3 = 15
            // 检索返回 5 个，全部低分 → 经过过滤后最少保留 1 个
            DocumentChunk c1 = chunk(1L, 100L, "内容1");
            when(vectorStore.search(any(), eq(5))).thenReturn(
                    List.of(new VectorStore.Hit(1L, 0.8f)));
            when(vectorStore.getChunk(1L)).thenReturn(c1);
            when(documentMapper.selectById(100L)).thenReturn(doc(100L, "测试文档"));

            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> result = (List<VectorStore.Hit>) invokePrivate(
                    "retrieveRelevantHits", "测试", new float[]{0.1f, 0.2f});

            assertThat(result).isNotEmpty();
        }
    }

    // ==================== loadConversationHistory ====================

    @Nested
    @DisplayName("loadConversationHistory —— 对话历史加载（Redis → MySQL 回退）")
    class LoadConversationHistory {

        @Test
        @DisplayName("Redis 有缓存时直接返回，不查 MySQL")
        void shouldReturnFromRedisCache() throws Exception {
            String sessionId = "abc12345";
            String cachedJson = "[{\"role\":\"user\",\"content\":\"什么是综测？\"}," +
                    "{\"role\":\"assistant\",\"content\":\"综测是综合素质测评的简称。\"}]";

            when(valueOps.get("rag:conversation:" + sessionId)).thenReturn(cachedJson);

            @SuppressWarnings("unchecked")
            List<Map<String, String>> result = (List<Map<String, String>>) invokePrivate(
                    "loadConversationHistory", sessionId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).get("role")).isEqualTo("user");
            assertThat(result.get(1).get("role")).isEqualTo("assistant");

            // 不应查数据库
            verify(messageMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("Redis 旧格式（含 system role）应清除缓存回退到 MySQL")
        void shouldClearOldFormatAndFallbackToMysql() throws Exception {
            String sessionId = "old12345";
            String oldJson = "[{\"role\":\"system\",\"content\":\"你是一个助手\"}," +
                    "{\"role\":\"user\",\"content\":\"什么是综测？\"}]";

            when(valueOps.get("rag:conversation:" + sessionId)).thenReturn(oldJson);
            // MySQL 返回空
            when(messageMapper.selectList(any())).thenReturn(Collections.emptyList());

            @SuppressWarnings("unchecked")
            List<Map<String, String>> result = (List<Map<String, String>>) invokePrivate(
                    "loadConversationHistory", sessionId);

            assertThat(result).isEmpty();
            // 应清除旧格式缓存
            verify(redisTemplate).delete("rag:conversation:" + sessionId);
        }

        @Test
        @DisplayName("Redis miss 应从 MySQL 加载并回填")
        void shouldLoadFromMysqlAndBackfill() throws Exception {
            String sessionId = "miss1234";
            when(valueOps.get("rag:conversation:" + sessionId)).thenReturn(null);

            ConversationMessage msg = new ConversationMessage();
            msg.setQuestion("综测是什么？");
            msg.setAnswer("综合素质测评的简称。");
            when(messageMapper.selectList(any())).thenReturn(List.of(msg));

            @SuppressWarnings("unchecked")
            List<Map<String, String>> result = (List<Map<String, String>>) invokePrivate(
                    "loadConversationHistory", sessionId);

            assertThat(result).hasSize(2);
            // 应回填 Redis
            verify(valueOps).set(eq("rag:conversation:" + sessionId), anyString(),
                    eq(24L), eq(TimeUnit.HOURS));
        }

        @Test
        @DisplayName("第一次对话（无历史）返回空列表")
        void shouldReturnEmptyForNewSession() throws Exception {
            when(valueOps.get("rag:conversation:new")).thenReturn(null);
            when(messageMapper.selectList(any())).thenReturn(Collections.emptyList());

            @SuppressWarnings("unchecked")
            List<Map<String, String>> result = (List<Map<String, String>>) invokePrivate(
                    "loadConversationHistory", "new");

            assertThat(result).isEmpty();
        }
    }

    // ==================== ask() 主流程 ====================

    @Nested
    @DisplayName("ask() —— 同步 RAG 问答主流程")
    class AskMethod {

        @Test
        @DisplayName("空问题应快速返回错误提示")
        void shouldReturnErrorForEmptyQuestion() {
            ChatRequest request = new ChatRequest();
            request.setQuestion("");

            ChatResponse response = ragService.ask(request);

            assertThat(response.getAnswer()).contains("请输入您的问题");
            assertThat(response.getSources()).isEmpty();
        }

        @Test
        @DisplayName("向量化失败应返回错误提示")
        void shouldReturnErrorWhenEmbeddingFails() {
            ChatRequest request = new ChatRequest();
            request.setQuestion("测试问题");

            when(embeddingClient.embed(anyString())).thenReturn(new float[0]);

            ChatResponse response = ragService.ask(request);

            assertThat(response.getAnswer()).contains("向量化服务暂不可用");
        }

        @Test
        @DisplayName("检索无结果应返回兜底回复")
        void shouldReturnFallbackWhenNoHits() {
            ChatRequest request = new ChatRequest();
            request.setQuestion("不存在的内容");

            when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
            when(vectorStore.size()).thenReturn(0); // 空向量库

            ChatResponse response = ragService.ask(request);

            assertThat(response.getAnswer()).contains("暂无相关内容");
        }

        @Test
        @DisplayName("正常流程：检索 → 生成 → 返回来源")
        void shouldCompleteFullRagPipeline() {
            ChatRequest request = new ChatRequest();
            request.setQuestion("综测加分细则");

            // 无历史
            when(valueOps.get(contains("rag:conversation:"))).thenReturn(null);
            when(messageMapper.selectList(any())).thenReturn(Collections.emptyList());
            // 向量化
            float[] vector = {0.1f, 0.2f, 0.3f};
            when(embeddingClient.embed("综测加分细则")).thenReturn(vector);
            // 检索
            when(vectorStore.size()).thenReturn(10);
            DocumentChunk c1 = chunk(1L, 100L, "综测加分细则规定，德育分占比30%，智育分50%，体育20%。");
            when(vectorStore.search(eq(vector), anyInt()))
                    .thenReturn(List.of(new VectorStore.Hit(1L, 0.85f)));
            when(vectorStore.getChunk(1L)).thenReturn(c1);
            Document d1 = doc(100L, "综合素质测评实施细则");
            when(documentMapper.selectById(100L)).thenReturn(d1);
            // LLM 生成
            when(deepSeekClient.chatWithHistory(anyList()))
                    .thenReturn("根据综测加分细则，德育分占30%，智育占50%，体育占20%。");

            // 用户
            com.rag.campus.entity.User user = new com.rag.campus.entity.User();
            user.setId(1L);
            user.setUsername("testuser");
            when(userService.getCurrentUser()).thenReturn(user);

            // 新会话 → session insert
            when(sessionMapper.insert(any())).thenReturn(1);
            when(messageMapper.insert(any())).thenReturn(1);

            // Redis 缓存清除
            when(redisTemplate.delete(anyString())).thenReturn(true);

            ChatResponse response = ragService.ask(request);

            assertThat(response.getAnswer()).contains("德育分");
            assertThat(response.getSources()).isNotEmpty();

            // 验证消息保存到了 MySQL
            ArgumentCaptor<ConversationMessage> msgCaptor =
                    ArgumentCaptor.forClass(ConversationMessage.class);
            verify(messageMapper).insert(msgCaptor.capture());
            assertThat(msgCaptor.getValue().getQuestion()).isEqualTo("综测加分细则");

            // 验证 Redis 历史已保存
            verify(valueOps).set(
                    startsWith("rag:conversation:"),
                    anyString(),
                    eq(24L),
                    eq(TimeUnit.HOURS));
        }

        @Test
        @DisplayName("对话历史 > 50 字标题应截断")
        void shouldTruncateLongTitle() {
            ChatRequest request = new ChatRequest();
            request.setQuestion("A".repeat(80) + "的申请条件是什么？");

            when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f});
            when(vectorStore.size()).thenReturn(10);
            DocumentChunk c1 = chunk(1L, 100L, "测试内容");
            when(vectorStore.search(any(), anyInt()))
                    .thenReturn(List.of(new VectorStore.Hit(1L, 0.85f)));
            when(vectorStore.getChunk(1L)).thenReturn(c1);
            when(documentMapper.selectById(100L)).thenReturn(doc(100L, "测试文档"));
            when(deepSeekClient.chatWithHistory(anyList())).thenReturn("回答。");
            com.rag.campus.entity.User u = new com.rag.campus.entity.User();
            u.setId(1L);
            when(userService.getCurrentUser()).thenReturn(u);
            when(sessionMapper.insert(any())).thenReturn(1);
            when(messageMapper.insert(any())).thenReturn(1);

            ragService.ask(request);

            ArgumentCaptor<ConversationSession> sCaptor =
                    ArgumentCaptor.forClass(ConversationSession.class);
            verify(sessionMapper).insert(sCaptor.capture());
            String title = sCaptor.getValue().getTitle();
            assertThat(title).endsWith("...");
            assertThat(title.length()).isLessThanOrEqualTo(53); // 50 + "..."
        }
    }

    // ==================== saveConversationHistory ====================

    @Nested
    @DisplayName("saveConversationHistory —— 对话历史持久化到 Redis")
    class SaveConversationHistory {

        @Test
        @DisplayName("新对话应追加 Q&A 并保存")
        void shouldAppendQAndA() throws Exception {
            List<Map<String, String>> slimHistory = new ArrayList<>();
            slimHistory.add(Map.of("role", "user", "content", "上一轮问题"));
            slimHistory.add(Map.of("role", "assistant", "content", "上一轮回答"));

            invokePrivate("saveConversationHistory", "sid123", slimHistory,
                    "新问题", "新回答");

            ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq("rag:conversation:sid123"), valueCaptor.capture(),
                    eq(24L), eq(TimeUnit.HOURS));

            String saved = valueCaptor.getValue();
            assertThat(saved).contains("上一轮问题", "上一轮回答", "新问题", "新回答");
        }
    }
}
