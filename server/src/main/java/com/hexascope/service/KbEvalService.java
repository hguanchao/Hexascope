/*
 * 文件说明：知识库召回评估服务，负责用例管理、批量运行与结果汇总。
 */
package com.hexascope.service;

import cn.hutool.core.map.MapUtil;
import cn.hutool.json.JSONUtil;
import com.hexascope.ai.kb.EvalMetricsCalculator;
import com.hexascope.ai.kb.HybridSearchService;
import com.hexascope.ai.kb.KbFilter;
import com.hexascope.common.PageResult;
import com.hexascope.model.dto.CreateEvalCaseRequest;
import com.hexascope.model.dto.RunEvalRequest;
import com.hexascope.model.vo.KbEvalCaseVO;
import com.hexascope.model.vo.KbEvalRunVO;
import com.hexascope.model.vo.KbEvalSummaryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库召回评估服务。
 *
 * <p>金标来源两种：导入评分表时按行自动生成自标注用例（弱监督），
 * 以及人工管理界面补充的用例。批量运行时每个用例在“纯向量 / 混合检索”
 * 两种配置下各测一遍，计算 recall@k / precision@k / MRR 落库，
 * 供评估面板对比混合检索带来的召回提升。</p>
 *
 * @author Hexascope Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbEvalService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int AUTO_CASE_QUERY_MAX_CHARS = 300;
    private static final int DEFAULT_TOP_K = 20;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.65;
    private static final String MODE_VECTOR = "vector";
    private static final String MODE_HYBRID = "hybrid";

    private final JdbcTemplate jdbcTemplate;
    private final HybridSearchService hybridSearchService;

    /**
     * 导入评分表后按行自动生成/更新自标注用例（导入钩子调用）。
     *
     * <p>用行级维度/等级/严重度 + 内容前缀作 query，该行入库后的分块 ID 作金标；
     * 重复导入同一行时按 (source, row_num) 更新，不产生重复用例。</p>
     */
    public void upsertAutoCases(String sourceIdText, List<Document> rowDocuments) {
        for (Document rowDocument : rowDocuments) {
            try {
                Map<String, Object> metadata = rowDocument.getMetadata();
                String source = stringValue(metadata.get("source"));
                Object rowValue = metadata.get("row");
                int rowNum = rowValue instanceof Number number ? number.intValue() : -1;
                if (source.isEmpty() || rowNum < 0) {
                    continue;
                }
                List<String> expectedIds = queryChunkIds(sourceIdText, source, rowNum);
                if (expectedIds.isEmpty()) {
                    continue;
                }
                jdbcTemplate.update(
                        "INSERT INTO kb_eval_case"
                                + " (query, expected_doc_ids, dimension, source, row_num, note, created_at, updated_at)"
                                + " VALUES (?, ?::jsonb, ?, ?, ?, ?, NOW(), NOW())"
                                + " ON CONFLICT (source, row_num) DO UPDATE SET"
                                + " query = EXCLUDED.query, expected_doc_ids = EXCLUDED.expected_doc_ids,"
                                + " dimension = EXCLUDED.dimension, note = EXCLUDED.note, updated_at = NOW()",
                        buildAutoQuery(rowDocument),
                        JSONUtil.toJsonStr(expectedIds),
                        stringValue(metadata.get("dimension")).isEmpty() ? null : stringValue(metadata.get("dimension")),
                        source, rowNum,
                        "导入自动生成");
            } catch (Exception e) {
                log.warn("自动生成评估用例失败，跳过该行: {}", e.getMessage());
            }
        }
    }

    /**
     * 创建人工补充的评估用例。
     */
    public void createCase(CreateEvalCaseRequest request) {
        jdbcTemplate.update(
                "INSERT INTO kb_eval_case (query, expected_doc_ids, dimension, note, created_at, updated_at)"
                        + " VALUES (?, ?::jsonb, ?, ?, NOW(), NOW())",
                request.query().trim(),
                JSONUtil.toJsonStr(request.expectedDocIds() == null ? List.of() : request.expectedDocIds()),
                request.dimension(),
                request.note());
    }

    /**
     * 删除评估用例（关联运行结果级联删除）。
     */
    public void deleteCase(String id) {
        int deleted = jdbcTemplate.update("DELETE FROM kb_eval_case WHERE id = CAST(? AS uuid)", id);
        if (deleted == 0) {
            throw new IllegalArgumentException("评估用例不存在: " + id);
        }
    }

    /**
     * 分页查询评估用例。
     */
    public PageResult<KbEvalCaseVO> listCases(int page, int pageSize, String dimension) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safePageSize;

        List<Object> args = new ArrayList<>();
        String whereSql = "";
        if (dimension != null && !dimension.isBlank()) {
            whereSql = "WHERE dimension = ?";
            args.add(dimension.trim());
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_eval_case " + whereSql, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safePageSize);
        pageArgs.add(offset);
        List<KbEvalCaseVO> items = jdbcTemplate.query(
                "SELECT id::text AS id, query, expected_doc_ids::text AS expected_ids, dimension,"
                        + " source, row_num, note, jsonb_array_length(expected_doc_ids) AS expected_count,"
                        + " created_at, updated_at"
                        + " FROM kb_eval_case " + whereSql
                        + " ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new KbEvalCaseVO(
                        rs.getString("id"),
                        rs.getString("query"),
                        JSONUtil.toList(rs.getString("expected_ids"), String.class),
                        rs.getString("dimension"),
                        rs.getString("source"),
                        (Integer) rs.getObject("row_num"),
                        rs.getString("note"),
                        rs.getInt("expected_count"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)),
                pageArgs.toArray());

        return PageResult.of(items, total == null ? 0L : total, safePage, safePageSize);
    }

    /**
     * 批量运行全部用例，返回执行的检索次数。
     */
    public int runAll(RunEvalRequest request) {
        String mode = request == null || request.mode() == null || request.mode().isBlank()
                ? "all" : request.mode().trim();
        int topK = request != null && request.topK() != null && request.topK() > 0
                ? request.topK() : DEFAULT_TOP_K;
        double threshold = request != null && request.similarityThreshold() != null
                ? request.similarityThreshold() : DEFAULT_SIMILARITY_THRESHOLD;

        List<KbEvalCaseRow> cases = loadAllCases();
        int executed = 0;
        for (KbEvalCaseRow evalCase : cases) {
            if (MODE_VECTOR.equals(mode) || "all".equals(mode)) {
                runOne(evalCase, false, topK, threshold);
                executed++;
            }
            if (MODE_HYBRID.equals(mode) || "all".equals(mode)) {
                runOne(evalCase, true, topK, threshold);
                executed++;
            }
        }
        log.info("评估批量运行完成: cases={}, executed={}, mode={}", cases.size(), executed, mode);
        return executed;
    }

    /**
     * 运行单个用例并落库指标。
     */
    private void runOne(KbEvalCaseRow evalCase, boolean hybridEnabled, int topK, double threshold) {
        long start = System.currentTimeMillis();
        List<Document> documents;
        try {
            documents = hybridSearchService.search(
                    evalCase.query(), topK, threshold, KbFilter.ACTIVE_SOURCE, hybridEnabled);
        } catch (Exception e) {
            // 单用例检索失败记录空指标，不中断整批运行。
            log.warn("评估检索失败: caseId={}, hybrid={}: {}", evalCase.id(), hybridEnabled, e.getMessage());
            documents = List.of();
        }
        long latency = System.currentTimeMillis() - start;
        List<String> retrieved = documents.stream().map(Document::getId).toList();
        EvalMetricsCalculator.EvalMetrics metrics =
                EvalMetricsCalculator.compute(retrieved, evalCase.expectedIds());

        Map<String, Object> config = MapUtil.<String, Object>builder("mode", hybridEnabled ? MODE_HYBRID : MODE_VECTOR)
                .put("topK", topK)
                .put("similarityThreshold", threshold)
                .put("hybridEnabled", hybridEnabled)
                .build();
        Map<String, Object> metricJson = MapUtil.<String, Object>builder("recall", metrics.recall())
                .put("precision", metrics.precision())
                .put("mrr", metrics.mrr())
                .put("hitCount", metrics.hitCount())
                .put("expectedCount", metrics.expectedCount())
                .put("retrievedCount", metrics.retrievedCount())
                .put("firstHitRank", metrics.firstHitRank())
                .put("latencyMs", latency)
                .build();
        jdbcTemplate.update("INSERT INTO kb_eval_run (case_id, config, metrics) VALUES (?, ?::jsonb, ?::jsonb)",
                UUID.fromString(evalCase.id()),
                JSONUtil.toJsonStr(config),
                JSONUtil.toJsonStr(metricJson));
    }

    /**
     * 分页查询运行结果（可按用例过滤）。
     */
    public PageResult<KbEvalRunVO> listRuns(String caseId, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safePageSize;

        List<Object> args = new ArrayList<>();
        String whereSql = "";
        if (caseId != null && !caseId.isBlank()) {
            whereSql = "WHERE case_id = CAST(? AS uuid)";
            args.add(caseId.trim());
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_eval_run " + whereSql, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safePageSize);
        pageArgs.add(offset);
        List<KbEvalRunVO> items = jdbcTemplate.query(
                "SELECT id::text AS id, case_id::text AS case_id, config::text AS config,"
                        + " metrics::text AS metrics, executed_at"
                        + " FROM kb_eval_run " + whereSql
                        + " ORDER BY executed_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new KbEvalRunVO(
                        rs.getString("id"),
                        rs.getString("case_id"),
                        JSONUtil.parseObj(rs.getString("config")),
                        JSONUtil.parseObj(rs.getString("metrics")),
                        rs.getObject("executed_at", LocalDateTime.class)),
                pageArgs.toArray());

        return PageResult.of(items, total == null ? 0L : total, safePage, safePageSize);
    }

    /**
     * 汇总对比：每个用例的最新一次向量与混合运行指标并排返回。
     */
    public List<KbEvalSummaryVO> getSummary(String dimension, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        List<Object> args = new ArrayList<>();
        String whereSql = "";
        if (dimension != null && !dimension.isBlank()) {
            whereSql = "WHERE c.dimension = ?";
            args.add(dimension.trim());
        }
        args.add(safeLimit);

        List<SummaryRow> rows = jdbcTemplate.query("""
                        WITH latest AS (
                            SELECT DISTINCT ON (r.case_id, r.config ->> 'mode')
                                   r.case_id, r.config, r.metrics, r.executed_at
                            FROM kb_eval_run r
                            ORDER BY r.case_id, r.config ->> 'mode', r.executed_at DESC
                        )
                        SELECT c.id::text AS case_id, c.query, c.dimension,
                               l.config::text AS config, l.metrics::text AS metrics, l.executed_at
                        FROM latest l JOIN kb_eval_case c ON c.id = l.case_id
                        %s
                        ORDER BY c.updated_at DESC
                        LIMIT ?
                        """.formatted(whereSql),
                (rs, rowNum) -> new SummaryRow(
                        rs.getString("case_id"),
                        rs.getString("query"),
                        rs.getString("dimension"),
                        JSONUtil.parseObj(rs.getString("config")),
                        JSONUtil.parseObj(rs.getString("metrics")),
                        rs.getObject("executed_at", LocalDateTime.class)),
                args.toArray());

        Map<String, KbEvalSummaryVO> byCase = new LinkedHashMap<>();
        LocalDateTime latest = null;
        for (SummaryRow row : rows) {
            String mode = String.valueOf(row.config().get("mode"));
            KbEvalSummaryVO existing = byCase.get(row.caseId());
            double recall = doubleValue(row.metrics().get("recall"));
            double precision = doubleValue(row.metrics().get("precision"));
            double mrr = doubleValue(row.metrics().get("mrr"));
            if (MODE_HYBRID.equals(mode)) {
                KbEvalSummaryVO merged = new KbEvalSummaryVO(
                        existing != null ? existing.caseId() : row.caseId(),
                        existing != null ? existing.query() : row.query(),
                        existing != null ? existing.dimension() : row.dimension(),
                        existing != null ? existing.recallVector() : null,
                        existing != null ? existing.precisionVector() : null,
                        existing != null ? existing.mrrVector() : null,
                        recall, precision, mrr, row.executedAt());
                byCase.put(row.caseId(), merged);
            } else {
                KbEvalSummaryVO merged = new KbEvalSummaryVO(
                        existing != null ? existing.caseId() : row.caseId(),
                        existing != null ? existing.query() : row.query(),
                        existing != null ? existing.dimension() : row.dimension(),
                        recall, precision, mrr,
                        existing != null ? existing.recallHybrid() : null,
                        existing != null ? existing.precisionHybrid() : null,
                        existing != null ? existing.mrrHybrid() : null,
                        existing != null ? existing.executedAt() : row.executedAt());
                byCase.put(row.caseId(), merged);
            }
        }
        return new ArrayList<>(byCase.values());
    }

    /**
     * 构造自动用例的检索 query：来源 + 维度 + 等级/严重度 + 内容前缀。
     */
    private static String buildAutoQuery(Document rowDocument) {
        Map<String, Object> metadata = rowDocument.getMetadata();
        String text = rowDocument.getText() == null ? "" : rowDocument.getText().trim();
        String keywordPart = text.length() > AUTO_CASE_QUERY_MAX_CHARS
                ? text.substring(0, AUTO_CASE_QUERY_MAX_CHARS) : text;
        StringBuilder query = new StringBuilder();
        query.append(stringValue(metadata.get("source"))).append(' ');
        String dimension = stringValue(metadata.get("dimension"));
        if (!dimension.isEmpty()) {
            query.append(dimension).append(' ');
        }
        String level = stringValue(metadata.get("level"));
        if (!level.isEmpty()) {
            query.append(level).append(' ');
        }
        String severity = stringValue(metadata.get("severity"));
        if (!severity.isEmpty()) {
            query.append(severity).append(' ');
        }
        query.append(keywordPart);
        return query.toString().trim();
    }

    /**
     * 查询某行入库后的分块 ID。
     */
    private List<String> queryChunkIds(String sourceIdText, String source, int rowNum) {
        return jdbcTemplate.queryForList(
                "SELECT id::text FROM vector_store"
                        + " WHERE metadata ->> 'source_id' = ? AND metadata ->> 'source' = ?"
                        + " AND (metadata ->> 'row')::int = ?",
                String.class, sourceIdText, source, rowNum);
    }

    private List<KbEvalCaseRow> loadAllCases() {
        return jdbcTemplate.query(
                "SELECT id::text AS id, query, expected_doc_ids::text AS expected_ids FROM kb_eval_case",
                (rs, rowNum) -> new KbEvalCaseRow(
                        rs.getString("id"),
                        rs.getString("query"),
                        JSONUtil.toList(rs.getString("expected_ids"), String.class)));
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString() : "";
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0;
    }

    private record KbEvalCaseRow(String id, String query, List<String> expectedIds) {
    }

    private record SummaryRow(String caseId, String query, String dimension,
                              Map<String, Object> config, Map<String, Object> metrics,
                              LocalDateTime executedAt) {
    }
}