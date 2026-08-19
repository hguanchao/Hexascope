/*
 * 文件说明：AI 评审核心组件，负责组织需求内容、调用模型并返回评分结果。
 */
package com.hexascope.ai;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONException;
import com.hexascope.ai.kb.HybridSearchService;
import com.hexascope.ai.kb.KbFilter;
import com.hexascope.ai.kb.MetadataFilterFactory;
import com.hexascope.ai.rerank.RerankerClient;
import com.hexascope.ai.parser.AiResponseParser;
import com.hexascope.ai.parser.FallbackScoringStrategy;
import com.hexascope.ai.parser.ParsedReviewResult;
import com.hexascope.ai.prompt.ReviewPromptTemplate;
import com.hexascope.model.enums.ReviewDimension;
import com.hexascope.service.KnowledgeTraceService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 审查引擎（RAG 架构）
 *
 * <p>核心流程：
 * <ol>
 *   <li>从 pgvector 向量库检索与需求相关的评分标准</li>
 *   <li>将检索结果注入 Prompt 构建上下文</li>
 *   <li>调用通义千问 LLM 进行审查评分</li>
 *   <li>解析 LLM 输出为结构化结果</li>
 *   <li>LLM 不可用时降级到本地规则评分</li>
 * </ol>
 * </p>
 *
 * @author Hexascope Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiReviewEngine {

    private static final String REQUIREMENT_SUMMARY_QUERY_TEMPLATE = """
            需求质量评分标准检索
            标题：%s
            需求摘要：
            %s
            评分维度：完整性、明确性、可行性、价值对齐、可测试性、格式规范
            """;

    /**
     * 检索侧双保险：所有 query 统一附加“仅召回 active 知识来源”的内部过滤
     * （见 {@link KbFilter#ACTIVE_SOURCE}），版本升级删除旧文档再做物理删除，
     * 标记过滤保证即使出现遗漏也不会召回失效版本。
     */
    private static final KbFilter ACTIVE_SOURCE_FILTER = KbFilter.ACTIVE_SOURCE;

    /**
     * 单维度检索 query 模板。
     *
     * <p>评分标准通常按维度组织。每个维度单独检索，可以避免只命中标题、
     * 开头背景或某一个功能点，提升各维度评分标准的召回稳定性。</p>
     */
    private static final String DIMENSION_QUERY_TEMPLATE = """
            %s评分标准
            维度代码：%s
            需求标题：%s
            需求摘要：
            %s
            """;

    /**
     * 长需求分块抽取的系统提示词。
     *
     * <p>超长需求会先按顺序完整分块，再逐块抽取评分事实。这里强调“只抽取已出现信息”，
     * 是为了避免模型在抽取阶段提前脑补业务规则。</p>
     */
    private static final String REQUIREMENT_EXTRACT_SYSTEM_PROMPT = """
            你是需求文档结构化抽取器。你的任务是只基于当前需求片段抽取可用于后续评分的事实。

            请严格遵守：
            - 只抽取片段中明确出现的信息，不得补全、推测或改写成未出现的业务规则
            - 保留会影响评分的目标、角色、流程、规则、验收标准、边界条件、异常场景、非功能要求和待确认问题
            - 如果某类信息没有出现，写“未出现”
            - 输出精简中文，不要输出 JSON 以外的解释性文本
            """;

    /**
     * 长需求单块抽取的用户提示词。
     *
     * <p>片段编号和原文位置会进入提示词，方便后续排查某条评分依据来自需求的哪个区域。</p>
     */
    private static final String REQUIREMENT_EXTRACT_USER_PROMPT = """
            请抽取以下需求片段。

            需求标题：
            %s

            片段信息：
            第 %d / %d 段，原文位置 %d-%d

            片段内容：
            %s

            请按以下固定格式输出：
            目标与价值：
            用户或角色：
            功能流程：
            业务规则：
            验收标准：
            边界与异常：
            非功能要求：
            待确认问题：
            """;

    /**
     * RAG 检索返回的最大文档数（默认 10）。
     * 可配置：hexascope.rag.top-k
     */
    @Value("${hexascope.rag.top-k:10}")
    private int topK;

    /**
     * 余弦相似度阈值（默认 0.65）。
     * 可配置：hexascope.rag.similarity-threshold
     */
    @Value("${hexascope.rag.similarity-threshold:0.65}")
    private double similarityThreshold;

    /**
     * 单条检索 query 的最大字符数。
     *
     * <p>该限制只影响“找评分标准”的检索文本，不代表需求原文会被截断入库。</p>
     */
    @Value("${hexascope.rag.query-max-chars:4000}")
    private int queryMaxChars;

    /**
     * 长文本做位置抽样时最多抽取多少段。
     *
     * <p>该配置用于检索摘要和抽取失败兜底。真正的长需求评分会优先按顺序完整分块抽取。</p>
     */
    @Value("${hexascope.rag.query-max-count:3}")
    private int queryMaxCount;

    /**
     * 进入模型评分链路的单块需求上下文上限。
     *
     * <p>短需求会完整进入评分；超出该上限时，先按该长度完整切块并逐块抽取事实。</p>
     */
    @Value("${hexascope.review.description-max-chars:12000}")
    private int reviewDescriptionMaxChars;

    /**
     * 当前聊天模型名称。
     *
     * <p>流式调用拿不到完整 ChatResponse 元数据时，用配置值记录本次使用的模型。</p>
     */
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String chatModelName;

    private final ChatClient.Builder chatClientBuilder;
    /**
     * 混合检索：pgvector 向量路 + pg_trgm 关键词路融合召回，替代对 VectorStore 的直接调用。
     */
    private final HybridSearchService hybridSearchService;
    /**
     * 检索追踪：每次评分检索成功后记录 query、候选与耗时。
     */
    private final KnowledgeTraceService traceService;
    /**
     * Reranker 只做候选评分标准的二次排序。
     *
     * <p>如果 reranker 未启用或调用失败，内部会自动退回 pgvector 原始排序结果。</p>
     */
    private final RerankerClient rerankerClient;
    private final AiResponseParser responseParser;
    private final FallbackScoringStrategy fallbackScoring;

    /**
     * 执行 AI 需求审查
     *
     * @param title       需求标题
     * @param description 需求描述
     * @param priority    需求优先级
     * @param creator     创建人
     * @param kbFilters   知识库检索过滤条件（维度/来源/严重度），可为 null 表示不过滤
     * @param reviewId    审查记录 ID，用于检索追踪打点，可为空
     * @return AI 审查结果
     */
    @CircuitBreaker(name = "ai-review", fallbackMethod = "fallbackReview")
    @Retry(name = "ai-review")
    public AiReviewResult review(String title, String description, String priority, String creator,
                                 Map<String, List<String>> kbFilters, String reviewId) {
        long startTime = System.currentTimeMillis();

        // 先检索评分标准，再组织需求内容；评分标准决定打分尺度，需求内容决定评分依据。
        KbFilter kbFilter = MetadataFilterFactory.create(kbFilters);
        RetrievalResult retrieval = retrieveScoringStandards(title, description, kbFilter);
        String retrievedStandards = retrieval.standardsText();

        ChatClient chatClient = chatClientBuilder.build();

        // 系统提示词放评分规则和 RAG 标准，用户提示词放当前被评审需求。
        String systemPrompt = ReviewPromptTemplate.SYSTEM_PROMPT
                .replace("{retrieved_standards}", retrievedStandards);

        String userPrompt = ReviewPromptTemplate.USER_PROMPT
                .replace("{requirement_title}", StrUtil.nullToEmpty(title))
                .replace("{requirement_description}", buildReviewDescription(chatClient, title, description))
                .replace("{requirement_priority}", StrUtil.nullToEmpty(priority))
                .replace("{requirement_creator}", StrUtil.nullToEmpty(creator));

        // 对外部模型调用统一走流式聚合，兼容只返回 text/event-stream 的 OpenAI 兼容服务。
        Message systemMessage = new SystemMessage(systemPrompt);
        Message userMessage = new UserMessage(userPrompt);
        Prompt prompt = new Prompt(List.of(systemMessage, userMessage));

        String aiResponse;
        String modelName;
        try {
            aiResponse = callModel(chatClient, prompt);
            modelName = chatModelName;
        } catch (Exception e) {
            log.warn("LLM 调用失败，使用降级策略: {}", e.getMessage());
            ParsedReviewResult parsed = fallbackScoring.fallbackScore(title, description, priority);
            long latency = System.currentTimeMillis() - startTime;
            return new AiReviewResult(
                    parsed,
                    systemPrompt + "\n" + userPrompt,
                    StrUtil.EMPTY,
                    "fallback-rules",
                    (int) latency
            );
        }

        // 模型输出必须先解析成结构化对象；解析失败时用本地规则兜底，避免记录长期卡在评分中。
        ParsedReviewResult parsed;
        try {
            parsed = responseParser.parse(aiResponse);
        } catch (JSONException e) {
            log.error("LLM 输出解析失败，使用降级策略。响应: {}", aiResponse, e);
            parsed = fallbackScoring.fallbackScore(title, description, priority);
        }

        long latency = System.currentTimeMillis() - startTime;
        recordKbTrace(reviewId, retrieval);
        log.info("AI 审查完成，耗时: {}ms，模型: {}", latency, modelName);

        return new AiReviewResult(
                parsed,
                systemPrompt + "\n" + userPrompt,
                aiResponse,
                modelName,
                (int) latency
        );
    }

    /**
     * 记录本次检索的追踪数据（内部已做异常隔离，失败不阻断评分）。
     */
    private void recordKbTrace(String reviewId, RetrievalResult retrieval) {
        traceService.record(reviewId, retrieval.queries(), retrieval.candidates(),
                retrieval.selectedIds(), retrieval.retrievalMs(), retrieval.rerankMs(),
                retrieval.retrievalMs() + retrieval.rerankMs(), hybridSearchService.isEnabled());
    }

    /**
     * 降级审查方法（LLM 不可用时）
     */
    private AiReviewResult fallbackReview(String title, String description, String priority,
                                          String creator, Map<String, List<String>> kbFilters,
                                          String reviewId, Throwable throwable) {
        log.warn("AI 审查降级触发，原因: {}", throwable.getMessage());

        ParsedReviewResult parsed = fallbackScoring.fallbackScore(title, description, priority);

        return new AiReviewResult(
                parsed,
                StrUtil.EMPTY,
                StrUtil.EMPTY,
                "fallback-rules",
                0
        );
    }

    /**
     * 从向量库检索评分标准
     *
     * <p>完整链路：
     * 1. 基于需求摘要、分段内容和评分维度生成多条检索 query（每条可携带过滤条件）；
     * 2. 混合检索（向量 + 关键词）按 query 召回候选并去重；
     * 3. 过滤条件下召回不足时自动放宽为不过滤重查补齐；
     * 4. reranker 根据综合 query 对候选评分标准做精排；
     * 5. 将精排后的评分标准拼接进 Prompt。</p>
     */
    private RetrievalResult retrieveScoringStandards(String title, String description, KbFilter kbFilter) {
        List<QuerySpec> querySpecs = buildSearchQueries(title, description, kbFilter);
        long retrievalStart = System.currentTimeMillis();

        try {
            // 未启用 reranker 时使用 hexascope.rag.top-k；启用后使用 candidate-k 多召回一些候选。
            int candidateLimit = rerankerClient.candidateLimit(topK);
            Map<String, Document> documentsByKey = new LinkedHashMap<>();
            for (QuerySpec spec : querySpecs) {
                List<Document> matchedDocuments = hybridSearchService.search(
                        spec.query(), candidateLimit, similarityThreshold, spec.filter());
                mergeDocuments(documentsByKey, matchedDocuments);
            }

            // 过滤条件下召回不足时，只移除用户过滤放宽重查；内部过滤始终保留。
            if (kbFilter != null && !kbFilter.isEmpty() && documentsByKey.size() < candidateLimit) {
                log.warn("知识库过滤后召回不足: size={}, candidateLimit={}，放宽用户过滤重查",
                        documentsByKey.size(), candidateLimit);
                for (QuerySpec spec : querySpecs) {
                    mergeDocuments(documentsByKey, hybridSearchService.search(
                            spec.query(), candidateLimit, similarityThreshold, ACTIVE_SOURCE_FILTER));
                    if (documentsByKey.size() >= candidateLimit) {
                        break;
                    }
                }
            }

            List<Document> documents = new ArrayList<>(documentsByKey.values());
            long retrievalEnd = System.currentTimeMillis();
            List<String> queries = querySpecs.stream().map(QuerySpec::query).toList();

            if (CollUtil.isEmpty(documents)) {
                log.warn("RAG 检索结果为空，使用默认评分标准");
                return new RetrievalResult("暂无检索到相关评分标准，请按照通用需求质量规范进行评估。",
                        queries, List.of(), List.of(), retrievalEnd - retrievalStart, 0);
            }

            // pgvector 先粗召回，Reranker 再根据当前需求和全部评分维度做二次精排。
            long rerankStart = System.currentTimeMillis();
            List<Document> rerankedDocuments = rerankerClient.rerank(
                    buildRerankQuery(title, description), documents
            );
            long rerankEnd = System.currentTimeMillis();

            // 这里拼接后的文本会进入系统 Prompt，作为 AI 评分时参考的评分标准上下文。
            String standards = rerankedDocuments.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.info("RAG 检索完成: candidates={}, selected={}", documents.size(), rerankedDocuments.size());
            return new RetrievalResult(standards, queries,
                    candidatesSnapshot(documents),
                    rerankedDocuments.stream().filter(doc -> doc.getId() != null).map(Document::getId).toList(),
                    retrievalEnd - retrievalStart, rerankEnd - rerankStart);
        } catch (Exception e) {
            log.warn("RAG 检索失败，使用默认评分标准: {}", e.getMessage());
            return new RetrievalResult("检索失败，请按照通用需求质量规范进行评估。",
                    List.of(), List.of(), List.of(), 0, 0);
        }
    }

    /**
     * 候选快照（id + score），封顶 50 条用于检索追踪。
     */
    private static List<Map<String, Object>> candidatesSnapshot(List<Document> documents) {
        return documents.stream().limit(50).map(document -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", document.getId());
            item.put("score", document.getScore());
            return item;
        }).toList();
    }

    /**
     * 按文档 ID 合并检索结果，先到先得。
     */
    private static void mergeDocuments(Map<String, Document> documentsByKey, List<Document> matched) {
        for (Document document : matched) {
            String key = StrUtil.blankToDefault(document.getId(), document.getText());
            documentsByKey.putIfAbsent(key, document);
        }
    }

    /**
     * 构造用于 RAG 召回评分标准的多条检索 query。
     *
     * <p>这里的目标不是把需求全文塞给向量库，而是用“综合摘要 + 分段内容 + 维度 query”
     * 覆盖更多评分标准，降低长需求只命中开头内容的风险。
     * 每条 query 携带独立的过滤条件：用户过滤应用到全部 query，
     * 维度 query 在用户未指定维度过滤时自动限定本维度。</p>
     */
    private List<QuerySpec> buildSearchQueries(String title, String description, KbFilter kbFilter) {
        int maxChars = Math.max(1, queryMaxChars);
        int maxCount = Math.max(1, queryMaxCount);
        String safeTitle = StrUtil.nullToEmpty(title).trim();
        String safeDescription = StrUtil.nullToEmpty(description).trim();
        String fullQuery = StrUtil.format("{} {}", safeTitle, safeDescription).trim();
        // 用户过滤与内部过滤（仅召回 active 知识源）AND 合并后作为各 query 的基底。
        KbFilter baseFilter = mergeInternalFilter(kbFilter);
        if (StrUtil.isBlank(fullQuery)) {
            return List.of(new QuerySpec("需求质量评分标准", baseFilter));
        }

        List<QuerySpec> specs = new ArrayList<>();
        String summary = buildRequirementSummaryForQuery(safeDescription, maxChars);
        // 综合 query 负责召回通用质量标准，维度 query 负责补齐每个评分维度的专门标准。
        specs.add(new QuerySpec(
                limitText(String.format(REQUIREMENT_SUMMARY_QUERY_TEMPLATE, safeTitle, summary), maxChars),
                baseFilter));

        if (StrUtil.isBlank(safeDescription)) {
            addDimensionQueries(specs, safeTitle, "", maxChars, baseFilter);
            return specs;
        }

        String prefix = StrUtil.isBlank(safeTitle) ? "" : safeTitle + "\n";
        prefix = limitText(prefix, maxChars);
        int segmentMaxChars = Math.max(1, maxChars - prefix.length());
        int requiredCount = (int) Math.ceil((double) safeDescription.length() / segmentMaxChars);
        int count = Math.min(maxCount, requiredCount);
        int maxStart = Math.max(0, safeDescription.length() - segmentMaxChars);

        for (int i = 0; i < count; i++) {
            int start = count == 1 ? 0 : (int) Math.round((double) maxStart * i / (count - 1));
            int end = Math.min(safeDescription.length(), start + segmentMaxChars);
            String segment = safeDescription.substring(start, end).trim();
            String query = limitText(prefix + segment, maxChars);
            specs.add(new QuerySpec(query, baseFilter));
        }

        addDimensionQueries(specs, safeTitle, summary, maxChars, baseFilter);
        return specs;
    }

    /**
     * 构建最终送入评分 Prompt 的需求上下文。
     *
     * <p>短需求直接使用完整原文；长需求先做顺序完整分块抽取，再把每块抽取结果合并。
     * 如果分块抽取失败，会退回到带覆盖提示的抽样工作副本，并要求模型降低置信度。</p>
     */
    private String buildReviewDescription(ChatClient chatClient, String title, String description) {
        String text = StrUtil.nullToEmpty(description).trim();
        int maxChars = Math.max(1, reviewDescriptionMaxChars);
        if (text.length() <= maxChars) {
            return """
                    覆盖状态：完整需求原文，未截断

                    %s
                    """.formatted(text);
        }

        try {
            return buildExtractedLongRequirementContext(chatClient, title, text, maxChars);
        } catch (Exception e) {
            log.warn("长需求分块抽取失败，使用带覆盖提示的工作副本: {}", e.getMessage());
            return buildSegmentedReviewDescription(text, maxChars);
        }
    }

    /**
     * 长需求分块抽取失败时的兜底上下文。
     *
     * <p>这里明确写出“工作副本”和原文位置，避免模型误以为已经看到了完整需求。</p>
     */
    private String buildSegmentedReviewDescription(String text, int maxChars) {
        List<TextSegment> segments = splitTextByPosition(text, maxChars, Math.max(1, queryMaxCount));
        StringBuilder builder = new StringBuilder();
        builder.append("覆盖状态：需求过长，以下为按全文位置抽取的评分工作副本；原始需求已完整入库，本次输入未包含的细节必须降低置信度，不得脑补。\n");
        builder.append("原文字符数：").append(text.length()).append("\n");
        builder.append("工作副本字符上限：").append(maxChars).append("\n");
        builder.append("片段数量：").append(segments.size()).append("\n\n");
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            builder.append("### 片段 ").append(i + 1)
                    .append(" / ").append(segments.size())
                    .append("，原文位置 ").append(segment.start())
                    .append("-").append(segment.end())
                    .append("\n")
                    .append(segment.text())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    /**
     * 将超长需求按原文顺序完整切块，并逐块调用模型抽取评分事实。
     *
     * <p>最终评分不直接面对超长原文，而是面对每块的结构化抽取结果。
     * 这样既能覆盖完整需求，又能控制最终评分 Prompt 的长度和噪声。</p>
     */
    private String buildExtractedLongRequirementContext(ChatClient chatClient, String title, String text, int maxChars) {
        List<TextSegment> chunks = splitTextSequentially(text, maxChars);
        int maxExtractChars = Math.max(600, maxChars / Math.max(1, chunks.size()));
        List<String> extractedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            TextSegment chunk = chunks.get(i);
            Prompt extractPrompt = new Prompt(List.of(
                    new SystemMessage(REQUIREMENT_EXTRACT_SYSTEM_PROMPT),
                    new UserMessage(String.format(
                            REQUIREMENT_EXTRACT_USER_PROMPT,
                            StrUtil.nullToEmpty(title),
                            i + 1,
                            chunks.size(),
                            chunk.start(),
                            chunk.end(),
                            chunk.text()
                    ))
            ));
            String extracted = limitText(callModel(chatClient, extractPrompt), maxExtractChars);
            extractedChunks.add("### 片段 " + (i + 1) + " / " + chunks.size()
                    + "，原文位置 " + chunk.start() + "-" + chunk.end()
                    + "\n" + extracted.trim());
        }

        return """
                覆盖状态：长需求已按顺序完整分块抽取；以下为所有原文片段的结构化抽取结果，最终评分必须基于这些抽取事实和参考标准，不得脑补未出现的信息。
                原文字符数：%d
                分块数量：%d
                单块抽取结果字符上限：%d

                %s
                """.formatted(
                text.length(),
                chunks.size(),
                maxExtractChars,
                String.join("\n\n", extractedChunks)
        ).trim();
    }

    /**
     * 为每个评分维度追加一条检索 query。
     *
     * <p>例如“完整性评分标准”和“可测试性评分标准”可能分布在不同知识片段中，
     * 单条综合 query 不一定都能召回。</p>
     */
    private void addDimensionQueries(List<QuerySpec> specs, String title, String summary, int maxChars,
                                     KbFilter baseFilter) {
        for (ReviewDimension dimension : ReviewDimension.values()) {
            String query = limitText(String.format(
                    DIMENSION_QUERY_TEMPLATE,
                    dimension.getName(),
                    dimension.getCode(),
                    title,
                    summary
            ), maxChars);
            specs.add(new QuerySpec(query, dimensionFilter(baseFilter, dimension)));
        }
    }

    /**
     * 维度 query 的过滤条件：用户未指定维度过滤时自动限定本维度，指定了则以用户为准，
     * 避免自动限定与用户过滤叠加过严导致召回不足。
     */
    private KbFilter dimensionFilter(KbFilter baseFilter, ReviewDimension dimension) {
        if (baseFilter.isEmpty() || baseFilter.conditions().containsKey(MetadataFilterFactory.KEY_DIMENSION)) {
            return baseFilter;
        }
        return baseFilter.with(Map.of(MetadataFilterFactory.KEY_DIMENSION, List.of(dimension.getName())));
    }

    /**
     * 用户过滤与内部过滤合并：所有 query 统一附加 source_active = true，
     * 保证召回范围始终限定在生效的知识来源内。
     */
    private static KbFilter mergeInternalFilter(KbFilter kbFilter) {
        KbFilter userFilter = kbFilter != null ? kbFilter : KbFilter.EMPTY;
        return userFilter.with(Map.of("source_active", List.of("true")));
    }

    /**
     * 构建 reranker 使用的综合 query。
     *
     * <p>reranker 只负责给候选评分标准排序，因此这里用需求摘要和六个维度作为排序依据，
     * 避免只偏向需求开头或某一个分段。</p>
     */
    private String buildRerankQuery(String title, String description) {
        int maxChars = Math.max(1, queryMaxChars);
        String summary = buildRequirementSummaryForQuery(StrUtil.nullToEmpty(description).trim(), maxChars);
        return limitText(String.format(
                REQUIREMENT_SUMMARY_QUERY_TEMPLATE,
                StrUtil.nullToEmpty(title).trim(),
                summary
        ), maxChars);
    }

    /**
     * 为检索构建高信息密度摘要。
     *
     * <p>向量检索 query 不是越长越好，过长会稀释重点。这里按开头、中段、结尾抽样，
     * 用于覆盖长需求的不同区域。</p>
     */
    private String buildRequirementSummaryForQuery(String text, int maxChars) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        int summaryMaxChars = Math.max(1, maxChars / 2);
        if (text.length() <= summaryMaxChars) {
            return text;
        }
        return splitTextByPosition(text, summaryMaxChars, Math.max(1, queryMaxCount)).stream()
                .map(TextSegment::text)
                .collect(Collectors.joining("\n---\n"));
    }

    /**
     * 按全文位置抽样切分文本。
     *
     * <p>用于检索摘要和抽取失败兜底，目标是覆盖开头、中间、结尾，而不是只截取前缀。</p>
     */
    private List<TextSegment> splitTextByPosition(String text, int maxChars, int maxCount) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        int safeMaxChars = Math.max(1, maxChars);
        int safeMaxCount = Math.max(1, maxCount);
        int segmentMaxChars = Math.max(1, safeMaxChars / safeMaxCount);
        int requiredCount = (int) Math.ceil((double) text.length() / segmentMaxChars);
        int count = Math.min(safeMaxCount, requiredCount);
        int maxStart = Math.max(0, text.length() - segmentMaxChars);

        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int start = count == 1 ? 0 : (int) Math.round((double) maxStart * i / (count - 1));
            int end = Math.min(text.length(), start + segmentMaxChars);
            String segment = text.substring(start, end).trim();
            if (StrUtil.isNotBlank(segment)) {
                segments.add(new TextSegment(start, end, segment));
            }
        }
        return segments;
    }

    /**
     * 按顺序完整切分文本。
     *
     * <p>用于长需求分块抽取，所有片段按原文顺序覆盖，不做跳段抽样。</p>
     */
    private List<TextSegment> splitTextSequentially(String text, int chunkMaxChars) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        int safeChunkMaxChars = Math.max(1, chunkMaxChars);
        List<TextSegment> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += safeChunkMaxChars) {
            int end = Math.min(text.length(), start + safeChunkMaxChars);
            String chunk = text.substring(start, end).trim();
            if (StrUtil.isNotBlank(chunk)) {
                chunks.add(new TextSegment(start, end, chunk));
            }
        }
        return chunks;
    }

    /**
     * 调用聊天模型并把流式内容聚合成普通字符串。
     */
    private String callModel(ChatClient chatClient, Prompt prompt) {
        return StrUtil.nullToEmpty(chatClient.prompt(prompt)
                .stream()
                .content()
                .collect(Collectors.joining())
                .block());
    }

    /**
     * 按字符数截断文本。
     *
     * <p>这里只用于控制 query 或抽取结果长度，不能用于静默截断最终评分所需的原始需求。</p>
     */
    private String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private record TextSegment(int start, int end, String text) {
    }

    /**
     * 单条检索 query 及其携带的知识库过滤条件。
     */
    private record QuerySpec(String query, KbFilter filter) {
    }

    /**
     * 一次检索的完整结果，供评分使用并支撑追踪打点。
     *
     * @param standardsText 最终进入 Prompt 的评分标准文本
     * @param queries 本次检索生成的所有 query
     * @param candidates 进入精排前的候选快照（id + score）
     * @param selectedIds 精排后保留的文档 ID
     * @param retrievalMs 检索阶段耗时
     * @param rerankMs 精排阶段耗时
     */
    private record RetrievalResult(String standardsText, List<String> queries,
                                   List<Map<String, Object>> candidates, List<String> selectedIds,
                                   long retrievalMs, long rerankMs) {
    }
}
