/*
 * 文件说明：检索追踪服务，负责写入、查询和清理 kb_search_trace。
 */
package com.hexascope.service;

import cn.hutool.json.JSONUtil;
import com.hexascope.common.PageResult;
import com.hexascope.model.vo.KnowledgeTraceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 检索追踪服务。
 *
 * <p>每次 AI 评分检索写一行 trace：query 列表、候选（id+score）、精排结果与分阶段耗时。
 * 打点失败只告警不阻断评分主流程；支持按审查记录查询和按天清理旧数据。</p>
 *
 * @author Hexascope Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeTraceService {

    /**
     * 候选快照封顶条数，控制 trace 数据体积。
     */
    private static final int MAX_CANDIDATES_SNAPSHOT = 50;
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 写入一条检索追踪记录。
     */
    public void record(String reviewId, List<String> queries, List<Map<String, Object>> candidates,
                       List<String> selectedIds, Long retrievalMs, Long rerankMs,
                       Long totalMs, boolean hybridEnabled) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO kb_search_trace"
                            + " (review_id, queries, candidates, selected, retrieval_ms, rerank_ms, total_ms, hybrid_enabled)"
                            + " VALUES (?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?)",
                    parseUuidOrNull(reviewId),
                    JSONUtil.toJsonStr(queries == null ? List.of() : queries),
                    JSONUtil.toJsonStr(capCandidates(candidates)),
                    JSONUtil.toJsonStr(selectedIds == null ? List.of() : selectedIds),
                    retrievalMs, rerankMs, totalMs, hybridEnabled);
        } catch (Exception e) {
            log.warn("写入检索追踪失败，不影响评分主流程: {}", e.getMessage());
        }
    }

    /**
     * 按审查记录分页查询追踪记录（reviewId 为空时查询全部）。
     */
    public PageResult<KnowledgeTraceVO> queryByReview(String reviewId, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safePageSize;

        List<Object> args = new ArrayList<>();
        String whereSql = "";
        if (reviewId != null && !reviewId.isBlank()) {
            whereSql = "WHERE review_id = ?";
            args.add(UUID.fromString(reviewId));
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_search_trace " + whereSql, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safePageSize);
        pageArgs.add(offset);
        List<KnowledgeTraceVO> items = jdbcTemplate.query(
                "SELECT id::text AS id, review_id::text AS review_id, queries::text AS queries,"
                        + " candidates::text AS candidates, selected::text AS selected,"
                        + " retrieval_ms, rerank_ms, total_ms, hybrid_enabled, created_at"
                        + " FROM kb_search_trace " + whereSql
                        + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new KnowledgeTraceVO(
                        rs.getString("id"),
                        rs.getString("review_id"),
                        JSONUtil.toList(rs.getString("queries"), String.class),
                        toMapList(rs.getString("candidates")),
                        JSONUtil.toList(rs.getString("selected"), String.class),
                        (Integer) rs.getObject("retrieval_ms"),
                        (Integer) rs.getObject("rerank_ms"),
                        (Integer) rs.getObject("total_ms"),
                        rs.getBoolean("hybrid_enabled"),
                        rs.getObject("created_at", LocalDateTime.class)),
                pageArgs.toArray());

        return PageResult.of(items, total == null ? 0L : total, safePage, safePageSize);
    }

    /**
     * JSON 数组转 Map 列表（Hutool 泛型推断对 raw Map.class 不友好，这里统一转换）。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toMapList(String json) {
        return (List<Map<String, Object>>) (List<?>) JSONUtil.toList(json, Map.class);
    }

    /**
     * 清理 N 天前的追踪记录，返回删除条数。
     */
    public int clean(int olderThanDays) {
        int days = Math.max(1, olderThanDays);
        return jdbcTemplate.update(
                "DELETE FROM kb_search_trace WHERE created_at < NOW() - ? * INTERVAL '1 day'", days);
    }

    private static List<Map<String, Object>> capCandidates(List<Map<String, Object>> candidates) {
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream().limit(MAX_CANDIDATES_SNAPSHOT).toList();
    }

    private static UUID parseUuidOrNull(String reviewId) {
        if (reviewId == null || reviewId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(reviewId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}