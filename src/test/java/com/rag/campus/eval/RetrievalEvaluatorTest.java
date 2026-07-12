package com.rag.campus.eval;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rag.campus.client.EmbeddingClient;
import com.rag.campus.entity.Document;
import com.rag.campus.entity.DocumentChunk;
import com.rag.campus.mapper.ConversationMessageMapper;
import com.rag.campus.mapper.ConversationSessionMapper;
import com.rag.campus.mapper.DocumentMapper;
import com.rag.campus.service.impl.RagServiceImpl;
import com.rag.campus.support.VectorStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * RAG 检索质量评测器
 * <p>
 * 基于 test-cases.json 中的 30 条评测用例，对 RagServiceImpl 的检索管道做全量评测。
 * <p>
 * 设计思路：
 * 1. 使用 KeyWordVectorStore（关键词匹配）替代真实的向量语义检索，
 *    从而消除 Embedding API 的不确定性，聚焦测试检索后处理管道（关键词加权、
 *    标题匹配、阈值过滤、多样性截断）。
 * 2. 文档内容基于真实文档提取的关键事实编写，确保测试结果有实际意义。
 * 3. 输出 Recall@5、Precision@5、MRR、Hit Rate 四项指标。
 *
 * <pre>
 *   运行方式：
 *     mvn test -Dtest=RetrievalEvaluatorTest
 * </pre>
 */
@DisplayName("RAG 检索质量评测")
class RetrievalEvaluatorTest {

    private static final String TEST_CASES_PATH = "eval/test-cases.json";
    private static final int TOP_K = 5;

    private static List<TestCase> testCases;
    private static RagServiceImpl ragService;
    private static KeywordVectorStore keywordStore;

    // ==================== 数据模型 ====================

    record TestCase(int id, String category, String difficulty, String question,
                    List<String> relevantDocTitles, List<String> expectedAnswerContains, String note) {
    }

    record EvalResult(int id, String category, String difficulty, String question,
                      List<String> retrievedTitles, List<String> relevantTitles,
                      int firstHitRank, boolean found) {
        double recallAt5() {
            if (relevantTitles.isEmpty()) return 1.0;
            long hit = retrievedTitles.stream().filter(relevantTitles::contains).count();
            return (double) hit / relevantTitles.size();
        }

        double precisionAt5() {
            if (retrievedTitles.isEmpty()) return 0.0;
            long hit = retrievedTitles.stream().filter(relevantTitles::contains).count();
            return (double) hit / retrievedTitles.size();
        }

        double reciprocalRank() {
            return firstHitRank > 0 ? 1.0 / firstHitRank : 0.0;
        }
    }

    // ==================== 初始化 ====================

    @BeforeAll
    static void setUp() throws Exception {
        testCases = loadTestCases();
        keywordStore = new KeywordVectorStore();
        loadDocuments(keywordStore);

        ragService = buildRagService(keywordStore);
    }

    @SuppressWarnings("unchecked")
    static List<TestCase> loadTestCases() throws Exception {
        try (InputStream is = RetrievalEvaluatorTest.class.getClassLoader()
                .getResourceAsStream(TEST_CASES_PATH)) {
            assert is != null : "评测用例文件未找到: " + TEST_CASES_PATH;
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject root = JSONUtil.parseObj(json);
            JSONArray arr = root.getJSONArray("testCases");
            List<TestCase> cases = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                cases.add(new TestCase(
                        obj.getInt("id"),
                        obj.getStr("category"),
                        obj.getStr("difficulty"),
                        obj.getStr("question"),
                        obj.getBeanList("relevantDocTitles", String.class),
                        obj.getBeanList("expectedAnswerContains", String.class),
                        obj.getStr("note")
                ));
            }
            return cases;
        }
    }

    /**
     * 构建 RagServiceImpl，Mock 掉外部依赖，注入 KeyWordVectorStore。
     * Conversation 历史等不参与检索的依赖全部 Mock 掉。
     */
    static RagServiceImpl buildRagService(KeywordVectorStore store) {
        // Mock 外部依赖
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        DocumentMapper documentMapper = mock(DocumentMapper.class);
        for (Map.Entry<Long, Document> e : store.documents.entrySet()) {
            when(documentMapper.selectById(e.getKey())).thenReturn(e.getValue());
        }

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        ConversationSessionMapper sessionMapper = mock(ConversationSessionMapper.class);
        ConversationMessageMapper messageMapper = mock(ConversationMessageMapper.class);

        RagServiceImpl service = new RagServiceImpl(
                null,               // deepSeekClient (检索评测不需要 LLM)
                embeddingClient,
                store,              // ← 注入关键词匹配向量库
                sessionMapper,
                messageMapper,
                documentMapper,
                redisTemplate,
                null                // userService (评测不需要)
        );

        // 注入 @Value 字段
        ReflectionTestUtils.setField(service, "topK", TOP_K);
        ReflectionTestUtils.setField(service, "minScore", 0.55);
        ReflectionTestUtils.setField(service, "maxHistory", 10);
        ReflectionTestUtils.setField(service, "ttlHours", 24);

        return service;
    }

    // ==================== 评测入口 ====================

    @Test
    @DisplayName("30 条评测用例全量检索质量评估")
    void evaluateAll() throws Exception {
        List<EvalResult> results = new ArrayList<>();

        for (TestCase tc : testCases) {
            // 1. 设置查询文本（KeyWordVectorStore.search() 用关键词匹配模拟语义检索）
            //    同时生成 dummy 向量（检索管道需要，但实际匹配不走向量）
            keywordStore.setQuery(tc.question());
            float[] dummyVector = {0.1f, 0.2f, 0.3f};

            // 2. 调用检索管道（retrieveRelevantHits 是 private 方法）
            @SuppressWarnings("unchecked")
            List<VectorStore.Hit> hits = (List<VectorStore.Hit>) invokeRetrieve(
                    ragService, tc.question(), dummyVector);

            // 3. 从 hits 解析出文档标题（前 TOP_K 个去重）
            Set<String> seen = new LinkedHashSet<>();
            List<String> retrievedTitles = new ArrayList<>();
            for (VectorStore.Hit hit : hits) {
                DocumentChunk chunk = keywordStore.getChunk(hit.getChunkId());
                if (chunk != null) {
                    Document doc = keywordStore.getDocument(chunk.getDocumentId());
                    if (doc != null && seen.add(doc.getTitle())) {
                        retrievedTitles.add(doc.getTitle());
                    }
                }
                if (retrievedTitles.size() >= TOP_K) break;
            }

            // 4. 计算排名和命中
            int firstHitRank = 0;
            for (int i = 0; i < retrievedTitles.size(); i++) {
                if (tc.relevantDocTitles().contains(retrievedTitles.get(i))) {
                    firstHitRank = i + 1;
                    break;
                }
            }
            boolean found = retrievedTitles.stream().anyMatch(tc.relevantDocTitles()::contains);

            results.add(new EvalResult(tc.id(), tc.category(), tc.difficulty(),
                    tc.question(), retrievedTitles, tc.relevantDocTitles(),
                    firstHitRank, found));
        }

        // 5. 计算指标
        double avgRecall = results.stream().mapToDouble(EvalResult::recallAt5).average().orElse(0);
        double avgPrecision = results.stream().mapToDouble(EvalResult::precisionAt5).average().orElse(0);
        double mrr = results.stream().mapToDouble(EvalResult::reciprocalRank).average().orElse(0);
        double hitRate = (double) results.stream().filter(EvalResult::found).count() / results.size();
        long totalFound = results.stream().filter(EvalResult::found).count();

        // 6. 打印报告
        printReport(results, avgRecall, avgPrecision, mrr, hitRate, totalFound);

        // 7. 按类别分组统计
        printCategoryBreakdown(results);

        // 8. 断言最低质量门槛
        //    关键词匹配只能测管道质量的下限，实际语义检索效果会显著更好
        //    这里断言管道不会把所有相关结果都过滤掉
        assertThat(hitRate)
                .as("Hit Rate: 至少命中一个相关文档的查询比例")
                .isGreaterThanOrEqualTo(0.40);

        assertThat(mrr)
                .as("MRR: 第一个相关结果的平均倒数排名")
                .isGreaterThanOrEqualTo(0.20);

        assertThat(avgRecall)
                .as("Recall@5: 前5条结果中相关文档的召回率")
                .isGreaterThanOrEqualTo(0.20);
    }

    // ==================== 报告输出 ====================

    private void printReport(List<EvalResult> results, double avgRecall, double avgPrecision,
                             double mrr, double hitRate, long totalFound) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  RAG 检索质量评测报告");
        System.out.println("=".repeat(70));
        System.out.printf("  用例总数:  %d%n", results.size());
        System.out.printf("  命中数:    %d (至少命中一个相关文档)%n", totalFound);
        System.out.println("─".repeat(70));
        System.out.printf("  Recall@%d:   %.2f%%%n", TOP_K, avgRecall * 100);
        System.out.printf("  Precision@%d: %.2f%%%n", TOP_K, avgPrecision * 100);
        System.out.printf("  MRR:       %.3f%n", mrr);
        System.out.printf("  Hit Rate:  %.1f%%%n", hitRate * 100);
        System.out.println("─".repeat(70));

        // 按难度统计
        for (String diff : List.of("easy", "medium", "hard")) {
            List<EvalResult> subset = results.stream()
                    .filter(r -> r.difficulty().equals(diff)).toList();
            if (subset.isEmpty()) continue;
            double hr = (double) subset.stream().filter(EvalResult::found).count() / subset.size();
            double rec = subset.stream().mapToDouble(EvalResult::recallAt5).average().orElse(0);
            System.out.printf("  [%s 难度] %d 条 | Hit Rate: %.0f%% | Recall@5: %.1f%%%n",
                    diff, subset.size(), hr * 100, rec * 100);
        }

        // 未命中的用例
        List<EvalResult> misses = results.stream().filter(r -> !r.found()).toList();
        if (!misses.isEmpty()) {
            System.out.println("─".repeat(70));
            System.out.println("  未命中用例:");
            for (EvalResult r : misses) {
                System.out.printf("    #%d [%s] %s%n", r.id(), r.difficulty(), r.question());
                System.out.printf("       期望: %s%n", r.relevantTitles());
                System.out.printf("       实际: %s%n", r.retrievedTitles());
            }
        }
        System.out.println("=".repeat(70) + "\n");
    }

    private void printCategoryBreakdown(List<EvalResult> results) {
        System.out.println("  按类别统计:");
        System.out.println("  ┌──────────────┬──────┬─────────┬──────────┐");
        System.out.println("  │ 类别         │ 条数 │ Hit Rate│ Recall@5 │");
        System.out.println("  ├──────────────┼──────┼─────────┼──────────┤");
        for (String cat : List.of("POLICY", "ACADEMIC", "GUIDE", "SCHOLARSHIP", "OTHER")) {
            List<EvalResult> subset = results.stream()
                    .filter(r -> r.category().equals(cat)).toList();
            if (subset.isEmpty()) continue;
            double hr = (double) subset.stream().filter(EvalResult::found).count() / subset.size();
            double rec = subset.stream().mapToDouble(EvalResult::recallAt5).average().orElse(0);
            System.out.printf("  │ %-12s │ %4d │  %5.0f%%  │  %6.1f%% │%n",
                    cat, subset.size(), hr * 100, rec * 100);
        }
        System.out.println("  └──────────────┴──────┴─────────┴──────────┘\n");
    }

    // ==================== Reflection helper ====================

    private Object invokeRetrieve(RagServiceImpl service, String query, float[] vector)
            throws Exception {
        Method m = RagServiceImpl.class.getDeclaredMethod(
                "retrieveRelevantHits", String.class, float[].class);
        m.setAccessible(true);
        return m.invoke(service, query, vector);
    }

    // ==================== 文档数据加载 ====================

    /**
     * 加载模拟文档及其分块到 KeyWordVectorStore。
     * 内容基于真实文档提取的关键事实。
     */
    static void loadDocuments(KeywordVectorStore store) {
        // ---- POLICY: 考试管理办法 ----
        long docExamId = addDoc(store, "关于印发《西北工业大学本科生考试管理办法》的通知", "POLICY",
                "考试迟到15分钟以上取消考试资格，按缺考处理。考试过程需携带学生证或一卡通。",
                "夹带资料、抄袭、传递物品、交换试卷等作弊行为给予留校察看处分。" +
                        "偷窃试卷、请人代考、组织作弊、出售考试答案等行为给予开除学籍处分。" +
                        "两次考试作弊累计开除学籍。",
                "学生对考试成绩有异议，应在成绩公布后15个工作日内申请复核。寒暑假期间不计入复核时限。",
                "雅思成绩6.5分以上或托福成绩95分以上可申请免修大学英语课程。",
                "缺课达三分之一以上或缺交作业三分之一以上不得参加课程考核。每学期最多申请2门课程免听。",
                "试题重复率：近三年试卷内容重复率不得超过30%。正考和补考试卷需准备2-3套等难度试题。");

        // ---- ACADEMIC: 材料学院综测办法 ----
        long docMaterialEvalId = addDoc(store, "材料学院本科生综合测评实施细则（2026版）", "ACADEMIC",
                "综合测评总分 G = 0.8×G1 + 0.1×G2 + 0.1×(G3+G4+G5) + G6。" +
                        "其中G1为课程学习成绩（GPA），G2为思想道德与身心素质，G3为科研创新，" +
                        "G4为全面发展，G5为公益服务，G6为违纪扣分（负值）。",
                "发表论文：Nature/Science/Cell加300分，引导性期刊加200分，学科性期刊加120分，公开刊物加10分。",
                "发明专利加100分（申请20分+授权80分），实用新型专利或软件著作权各加20分。",
                "学科竞赛：国际级特等奖150分，国家级一等奖120分，省级一等奖80分，校级一等奖30分。" +
                        "三航杯获奖按1.5倍系数加分。",
                "学生干部加分：学生会主席A档60分B档50分C档40分，班长A档30分B档25分C档20分。",
                "志愿服务：每小时0.1分，最高不超过15分（封顶150小时）。",
                "语言类加分：GRE320+/GMAT700+/TOEFL90+/IELTS7+各加10分。",
                "一票否决项：思想品德问题、违法违纪、学术不端、擅自离校、打架斗殴、赌博酗酒。" +
                        "弄虚作假者取消所有奖项和保研推免资格。",
                "综测中G2评分前30%评为90分及以上。违纪扣分：留校察看扣6分，记过扣5分，" +
                        "严重警告扣4分，警告扣3分，宿舍检查不合格扣1分。");

        // ---- ACADEMIC: 软件学院综测办法 ----
        addDoc(store, "西北工业大学软件学院本科生综合素质测评办法（2024版）", "ACADEMIC",
                // Chunk 0
                "综合测评总分 G = 0.1×G1 + 0.8×G2 + 0.1×(G3+G4+G5) + G6。" +
                        "G1为思想政治素质（百分制，60分及格，低于60分一票否决），G2为学业成绩（GPA），" +
                        "G3为体质健康，G4为美育素养，G5为劳动素养，G6为违纪扣分加种子银行加分。",
                // Chunk 1
                "G1评分等级：前10%得100分，10%-40%得95分，40%-80%得90分，80%-90%得85分，后10%得80分。",
                // Chunk 2
                "学业成绩G2计算：2021级 G2 = GPA×25。" +
                        "2022级及以后 G2 = 0.8×(GPA×25) + 0.2×(厚基础课程GPA×25)。" +
                        "厚基础课程包括：微积分、线性代数、大学物理、概率论与数理统计、计算方法、" +
                        "程序设计基础、离散数学、数据结构、软件工程导论、面向对象编程与设计、" +
                        "计算机网络、计算机组成原理、计算机操作系统、数据库系统、编译原理、" +
                        "算法分析与设计、软件测试、软件需求工程、信号与系统等22门课程。",
                // Chunk 3
                "学生干部加分：学生会主席A档30分B档24分C档18分，班长/团支书A档10分B档8分C档6分。",
                // Chunk 4
                "集体荣誉：国家级加40分，省级加20分，校级加8分，院级加4分。",
                "语言加分：GRE320/TOEFL90/IELTS7/GMAT700各加10分（每年限1项）。" +
                        "CET-4达到650分或CET-6达到600分加10分。",
                "学科竞赛加成：中国国际大学生创新大赛和挑战杯按150%系数计算。" +
                        "种子银行：参与活动次数X<3得0分，3≤X<6得0.5分，6≤X<10得1分，10≤X<15得2分，X≥15得3分。");

        // ---- ACADEMIC: 竞赛名录 ----
        addDoc(store, "附件-西北工业大学认定的本科生学科竞赛项目名录（2026年版）", "ACADEMIC",
                "竞赛项目按等级分为A1、A2、A3三类，共139项。其中A1级91项，A2级22项，A3级26项。" +
                        "A1竞赛系数为满分1.0，A2系数0.9，A3系数0.8。",
                "A1类竞赛包括：中国国际大学生创新大赛、挑战杯全国大学生课外学术科技作品竞赛、" +
                        "挑战杯中国大学生创业计划竞赛、ACM-ICPC国际大学生程序设计竞赛、" +
                        "全国大学生数学建模竞赛、全国大学生电子设计竞赛、全国大学生智能汽车竞赛、" +
                        "中国机器人大赛暨Robocup机器人世界杯中国赛、ASC世界大学生超级计算机竞赛、" +
                        "全国大学生英语竞赛、国际大学生数学建模竞赛(MCM/ICM)、" +
                        "蓝桥杯全国软件和信息技术专业人才大赛、中国软件杯大学生软件设计大赛等。");

        // ---- ACADEMIC: 软件工程培养方案 ----
        addDoc(store, "2021级信息大类-软件工程专业培养方案", "ACADEMIC",
                "软件工程专业总学分要求167学分。其中通识类不少于82.5学分，" +
                        "学科专业类不少于84.5学分，个性发展类建议16学分，素质拓展类不少于4学分。",
                "标准学制4年，弹性学习年限3-6年，最长在校时间不超过标准学制加2年。" +
                        "学位：工学学士。",
                "专业方向：互联网系统开发、数据科学与智能服务、智能媒体计算三个方向。",
                "毕业要求：掌握工程知识、问题分析、设计开发解决方案、研究能力、使用现代工具、" +
                        "工程与社会、环境与可持续发展、职业规范、个人与团队、沟通、" +
                        "项目管理、终身学习共12项核心能力。");

        // ---- GUIDE: 瓜兵速成指南 ----
        addDoc(store, "瓜兵速成指南（2025版）", "GUIDE",
                "长安校区宿舍分为星天苑、云天苑、海天苑三个区域。星天苑C-G和云天苑A-D为四人间，" +
                        "上床下桌，公共卫浴；星天苑A-B为四室一厅套间。",
                "床尺寸：星天苑和云天苑床长1.9米（加长2.1米），海天苑床长2.0米（加长2.2米）。",
                "学校地址：陕西省西安市长安区东大街道东祥路1号西北工业大学长安校区，邮编710129。",
                "GPA换算表：95-100分=4.1，90-94分=3.9，85-89分=3.7，81-84分=3.3，78-80分=3.0，" +
                        "75-77分=2.7，72-74分=2.3，68-71分=2.0，64-67分=1.7，60-63分=1.3，60分以下=0。",
                "校园网账号最多连接4台设备，月费50元不限量。校园WIFI信号名NWPU-FREE（教学区）" +
                        "和NWPU-WLAN（宿舍区）。VPN地址：vpn.nwpu.edu.cn。",
                "食堂：星天苑南餐厅、星天苑北餐厅、云天苑餐厅、海天苑餐厅共4个。外来人员加价20%。",
                "2025级军训时间：8月25日至9月7日。长安校区作息时间第一节8:30-9:15。");

        // ---- GUIDE: 四六级报名 ----
        addDoc(store, "全国大学英语四、六级考试报名操作手册", "GUIDE",
                "四六级考试报名网站：https://cet-kw.neea.edu.cn/，选择省份陕西。" +
                        "虚拟校区代码：610022（西北工业大学虚拟校区）。",
                "报名步骤：登录网站、确认学籍信息、选择实体校区（长安校区或友谊校区）、" +
                        "不要选候补报名、缴费后信息不可更改。口语考试为选考，需与笔试同一校区。");

        // ---- SCHOLARSHIP: 专项奖学金评选通知 ----
        addDoc(store, "关于做好2023-2024学年本科生专项奖学金评选工作的通知（2023-2024）", "SCHOLARSHIP",
                "国家奖学金和社会专项奖学金不能兼得。不同社会专项奖学金之间不能兼得。" +
                        "同一出资方的奖学金不能同时获得（如吴亚军奖学金和吴亚军助学金不能兼得）。",
                "航空工业一等奖学金需二年级以上学生，各学院推荐1人，综合测评排名前30%或获省部级竞赛三等奖以上。" +
                        "中航技奖学金仅面向大四学生，需综合排名前30%、CET-6成绩500分以上或外语B1以上。",
                "评选流程：发布条件→学院初审→不少于3个工作日公示→提交长安校区启真楼302。" +
                        "申请截止时间：航空工业10月8日16:00，其他10月30日11:00。");

        // ---- SCHOLARSHIP: 奖学金设置摘要 ----
        addDoc(store, "本科生专项奖学金设置及评选摘要（2023-2024）", "SCHOLARSHIP",
                "航空工业奖学金：一等10000元（10人）、二等6000元（24人），由中国航空工业集团出资。",
                "中航技奖学金：8000元（10人），由中航技进出口有限责任公司出资。",
                "吴亚军奖学金：10000元（7人）、8000元（17人）、5000元（155人），由校友吴亚军出资。",
                "科为奖学金：10000元（10人）、8000元（15人）、5000元（25人），由西安科为航天科技集团出资。",
                "小米优秀奖：5000元（20人），由北京小米公益基金会出资，优先家庭经济困难学生。",
                "华萌奖学金：10000元（18人），由深圳市华萌慈善基金会出资，无不及格科目且积极社会实践。",
                "铂力特奖学金：10000/8000/5000/3000元（共14人），由西安铂力特增材技术公司出资，" +
                        "面向6个学院大三及以上，特等前5%、一等前10%、二等前15%、三等前20%。",
                "宁波未来之星奖学金：10000元（20人），由宁波市人社局出资，大三理工科，优先国家级科研成果。");

        // ---- SCHOLARSHIP: 三星奖学金 ----
        addDoc(store, "关于做好2022-2023学年\"三星\"社会专项奖学金评选工作的通知", "SCHOLARSHIP",
                "三星奖学金本科生5000元/人（8人），硕士生7000元/人（8人）。" +
                        "面向学院：材料学院、机电学院、电子信息学院、自动化学院、计算机学院、软件学院、微电子学院。" +
                        "本科生面向大二和大三学生（以新大三为主），各学院推荐2名本科生和2名硕士生，" +
                        "由三星方面差额评选最终获奖者。",
                "三星奖学金与国家奖学金及其他社会专项奖学金不能兼得。" +
                        "申请截止时间：12月1日16:00。提交电子版至csas@nwpu.edu.cn，纸质版交启真楼302。");

        // ---- OTHER: 体测标准 ----
        addDoc(store, "体测各项评分标准", "OTHER",
                "男生1000米大一/大二及格标准为4分32秒，优秀标准为3分17秒。" +
                        "女生800米大一/大二及格标准为4分34秒，优秀标准为3分18秒。",
                "男生立定跳远大一/大二及格标准为208厘米，优秀标准为273厘米。" +
                        "女生立定跳远大一/大二及格标准为151厘米，优秀标准为207厘米。",
                "男生引体向上大一/大二及格标准为10次，优秀标准为19次。" +
                        "女生仰卧起坐大一/大二及格标准为26次，优秀标准为56次。",
                "BMI评分：正常范围男17.9-23.9，女17.2-23.9。" +
                        "偏瘦（男≤17.8/女≤17.1）80分，超重（24.0-27.9）80分，肥胖（≥28.0）60分。",
                "肺活量：男生大一大二优秀5040ml，及格3100ml；女生大一大二优秀3400ml，及格2000ml。" +
                        "50米跑：男生大一大二优秀6.7秒，及格9.1秒；女生优秀7.5秒，及格10.3秒。");

        // ---- OTHER: 评教通知 ----
        addDoc(store, "关于开展2024-2025学年秋季学期课程学生网上评教的通知", "OTHER",
                "评教时间：2025年1月2日9:00至1月12日17:00。" +
                        "评教平台：翱翔教务→评教管理→学生总结性评教。",
                "每位学生评教中优秀比例不能超过所评课程数的20%，系统自动限制。" +
                        "缺课1/3或作业缺交1/3的学生，其评教可被教师申请排除，截止1月19日。");

        // ---- OTHER: 社会实践 ----
        addDoc(store, "关于开展2025年寒假大学生社会实践活动的通知", "OTHER",
                "实践主题：青春为中国式现代化挺膺担当。6个类别：总师育人文化传承、岗位实习实践、" +
                        "乡情民情考察、服务家乡建设、学生大使招生实践、网络云实践。",
                "实践时长不少于5天。团队≤5人为分散实践，≥6人为集中实践。" +
                        "个人报告不少于1500字，集中团队报告不少于3000字。",
                "2025寒假实践时间：报名1月6日-21日，实施1月10日-2月16日，总结评优2月16日-3月中旬。" +
                        "本课程为素质拓展课（2学分）。抄袭造假取消评优资格。");

        System.out.println("  [评测] 已加载 " + store.size() + " 个模拟文档，共 "
                + store.totalChunks() + " 个 chunk\n");
    }

    static long addDoc(KeywordVectorStore store, String title, String category, String... chunkTexts) {
        long docId = store.nextDocId();
        Document doc = new Document();
        doc.setId(docId);
        doc.setTitle(title);
        doc.setFileType(category);
        store.putDocument(docId, doc);

        for (int i = 0; i < chunkTexts.length; i++) {
            long chunkId = store.nextChunkId();
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(chunkId);
            chunk.setDocumentId(docId);
            chunk.setChunkText(chunkTexts[i]);
            chunk.setChunkIndex(i);
            store.putChunk(chunkId, chunk);
        }
        return docId;
    }
}
