/*
 * 文件说明：后端业务服务，集中承载需求评审、配置、审计或外部系统连接逻辑。
 */
package com.hexascope.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hexascope.common.PageResult;
import com.hexascope.ai.workflow.ReviewWorkflow;
import com.hexascope.ai.workflow.ReviewWorkflowRequest;
import com.hexascope.ai.workflow.ReviewWorkflowResult;
import com.hexascope.mapper.ReviewHistoryMapper;
import com.hexascope.mapper.ReviewRecordMapper;
import com.hexascope.model.entity.ReviewHistory;
import com.hexascope.model.entity.ReviewRecord;
import com.hexascope.model.entity.ReviewWorkflowCheckpoint;
import com.hexascope.model.enums.ReviewDimension;
import com.hexascope.model.enums.ReviewWorkflowNode;
import com.hexascope.model.enums.ReviewStatus;
import com.hexascope.model.vo.ReviewDetailVO;
import com.hexascope.model.vo.ReviewListItemVO;
import com.hexascope.model.vo.StatsOverviewVO;
import com.hexascope.model.vo.TeamStatsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 需求审查 Service
 *
 * <p>该服务是需求审查业务的主入口，负责：
 * 1. 保存需求和触发异步 AI 评分；
 * 2. 回填 AI 评分结果和失败状态；
 * 3. 处理重新审查、人工状态更新和历史快照；
 * 4. 组装列表、详情和统计接口需要的数据。</p>
 *
 * @author Hexascope Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final String RETRIGGER_REDIS_KEY = "hexascope:retrigger:%s";
    private static final Duration RETRIGGER_COOLDOWN = Duration.ofMinutes(5);
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final String RETRIGGER_OPERATOR = "current_user";
    private static final String DEFAULT_OPERATOR = "current user";
    private static final String AI_REVIEW_FAILED_MESSAGE = "AI 评分失败，请稍后重新审查";

    /**
     * 允许人工设置的状态。
     *
     * <p>评分中和评分失败状态由系统控制，不能通过人工状态接口直接写入。</p>
     */
    private static final List<String> MANUAL_REVIEW_STATUSES = List.of(
            ReviewStatus.APPROVED.getCode(),
            ReviewStatus.REJECTED.getCode(),
            ReviewStatus.NEEDS_REVISION.getCode(),
            ReviewStatus.PENDING.getCode()
    );

    private final ReviewRecordMapper reviewRecordMapper;
    private final ReviewHistoryMapper reviewHistoryMapper;
    private final ReviewWorkflow reviewWorkflow;
    private final StringRedisTemplate redisTemplate;
    private final AuditLogService auditLogService;
    private final WorkflowCheckpointService workflowCheckpointService;

    /**
     * 创建需求并触发 AI 评分。
     *
     * <p>创建接口只同步保存一条“评分中”记录；AI 评分放到事务提交后异步执行，
     * 这样用户创建需求时不会被模型调用耗时阻塞。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public ReviewListItemVO createRequirementWithReview(String requirementId, String title, String description,
                                                         String priority, String creator, String workspaceId,
                                                         String teamId, Map<String, List<String>> kbFilters) {
        // 创建接口只负责保存需求，不同步等待 AI；评分在事务提交后由后台工作流完成。
        ReviewRecord record = buildPendingReviewRecord(requirementId, title, description, priority,
                creator, workspaceId, teamId, kbFilters);
        reviewRecordMapper.insert(record);
        ReviewWorkflowCheckpoint checkpoint = workflowCheckpointService.createFor(record);
        auditLogService.log("system", "REQUIREMENT_CREATED", "review", record.getId().toString(),
                MapUtil.<String, Object>builder("requirementId", requirementId)
                        .put("status", record.getStatus())
                        .build());
        runAfterCommit(() -> completeAiReview(record.getId(), checkpoint.getWorkflowInstanceId()));
        return toListItemVO(record);
    }

    /**
     * 构造“评分中”的初始记录。
     *
     * <p>总分、等级和维度分先留空，避免用户创建需求时被模型耗时或失败阻塞。</p>
     */
    private ReviewRecord buildPendingReviewRecord(String requirementId, String title, String description,
                                                  String priority, String creator, String workspaceId,
                                                  String teamId, Map<String, List<String>> kbFilters) {
        return ReviewRecord.builder()
                .requirementId(requirementId)
                .workspaceId(workspaceId)
                .teamId(teamId)
                .requirementTitle(title)
                .requirementDescription(description)
                .priority(priority)
                .creator(creator)
                .status(ReviewStatus.REVIEWING.getCode())
                .retriggerCount(0)
                .kbFilters(kbFilters)
                .build();
    }

    /**
     * 后台完成 AI 评分并回填数据库。
     *
     * <p>这里调用 ReviewWorkflow 执行 Graph 节点，服务层只处理记录读取、
     * 状态落库、缓存和审计日志。</p>
     */
    private void completeAiReview(UUID reviewId, String workflowInstanceId) {
        ReviewRecord record;
        ReviewWorkflowResult result = null;
        try {
            record = reviewRecordMapper.findByIdAndNotDeleted(reviewId);
            if (record == null) {
                log.warn("AI 评分记录不存在: reviewId={}", reviewId);
                return;
            }

            result = reviewWorkflow.run(ReviewWorkflowRequest.from(record, workflowInstanceId));
            applyWorkflowResult(record, result);

            reviewRecordMapper.updateById(record);
        } catch (Exception e) {
            log.error("AI 评分失败: reviewId={}", reviewId, e);
            if (result != null) {
                markWorkflowResultFailed(workflowInstanceId, result, e);
            }
            markReviewFailed(reviewId);
            return;
        }

        try {
            workflowCheckpointService.markCompleted(workflowInstanceId, buildWorkflowResultSnapshot(result));
        } catch (Exception e) {
            log.error("标记 AI 评分工作流断点完成失败: reviewId={}, workflowInstanceId={}",
                    reviewId, workflowInstanceId, e);
        }

        cacheReviewResult(record);
        try {
            auditLogService.log("system", "REVIEW_COMPLETED", "review", record.getId().toString(),
                    MapUtil.<String, Object>builder("requirementId", record.getRequirementId())
                            .put("score", record.getTotalScore())
                            .build());
        } catch (Exception e) {
            log.warn("记录 AI 评分完成审计日志失败: reviewId={}", reviewId, e);
        }
    }

    /**
     * 将评分失败显式落库，前端据此展示失败状态和重新审查入口。
     */
    private void markReviewFailed(UUID reviewId) {
        ReviewRecord record = reviewRecordMapper.findByIdAndNotDeleted(reviewId);
        if (record == null) {
            return;
        }
        record.setStatus(ReviewStatus.REVIEW_FAILED.getCode());
        record.setImprovementSuggestion(AI_REVIEW_FAILED_MESSAGE);
        record.setCompletedAt(LocalDateTime.now());
        reviewRecordMapper.updateById(record);
    }

    /**
     * 重新触发 AI 审查。
     *
     * <p>重新审查会先写 Redis 冷却锁，避免用户短时间连续点击导致重复调用模型。
     * 旧评分会保存到历史表，新评分字段会清空并重新进入“评分中”。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public ReviewRecord retriggerReview(String requirementId, String reason, String operator) {
        String lockKey = StrUtil.format(RETRIGGER_REDIS_KEY, requirementId);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", RETRIGGER_COOLDOWN);
        if (Boolean.FALSE.equals(acquired)) {
            Long ttl = redisTemplate.getExpire(lockKey);
            int retryAfter = ttl != null ? ttl.intValue() : 300;
            throw new RateLimitException("请求过于频繁，请 " + retryAfter + " 秒后重试");
        }

        ReviewRecord existing = reviewRecordMapper.findLatestByRequirementId(requirementId);
        if (existing == null) {
            throw new IllegalArgumentException("未找到需求 " + requirementId + " 的审查记录");
        }
        if (ReviewStatus.REVIEWING.getCode().equals(existing.getStatus())) {
            throw new IllegalArgumentException("AI 正在评分，暂不能重新审查");
        }

        // 重新审查前先保存旧结果，再清空评分字段，避免用户误以为旧分数是新评分。
        saveHistorySnapshot(existing, reason);
        existing.setStatus(ReviewStatus.REVIEWING.getCode());
        existing.setTotalScore(null);
        existing.setLevel(null);
        existing.setDimensionScores(null);
        existing.setAiSuggestions(null);
        existing.setImprovementSuggestion(null);
        existing.setAiModelUsed(null);
        existing.setAiLatencyMs(null);
        existing.setRawPrompt(null);
        existing.setRawAiResponse(null);
        existing.setRetriggerCount(existing.getRetriggerCount() + 1);
        existing.setCompletedAt(null);

        reviewRecordMapper.updateById(existing);
        ReviewWorkflowCheckpoint checkpoint = workflowCheckpointService.createFor(existing);

        auditLogService.log(operator, "REVIEW_RETRIGGERED", "review", existing.getId().toString(),
                MapUtil.<String, Object>builder("requirementId", requirementId)
                        .put("reason", StrUtil.nullToEmpty(reason))
                        .build());

        runAfterCommit(() -> completeAiReview(existing.getId(), checkpoint.getWorkflowInstanceId()));
        log.info("已触发异步重新审查: requirementId={}", requirementId);
        return existing;
    }

    /**
     * 分页查询审查记录
     *
     * <p>具体条件拼接在 Mapper XML 中完成，Service 只负责透传查询条件和分页参数。</p>
     */
    public IPage<ReviewRecord> queryReviews(String teamId, String creator, String status,
                                              Integer minScore, Integer maxScore,
                                              LocalDateTime startTime, LocalDateTime endTime,
                                              int page, int pageSize) {
        return reviewRecordMapper.pageByConditions(Page.of(page, pageSize),
                teamId, creator, status, minScore, maxScore, startTime, endTime);
    }

    /**
     * 查询审查分页并转换为前端列表项。
     */
    public PageResult<ReviewListItemVO> getReviewPage(String teamId, String creator, String status,
                                                       Integer minScore, Integer maxScore,
                                                       LocalDateTime startTime, LocalDateTime endTime,
                                                       int page, int pageSize) {
        IPage<ReviewRecord> records = queryReviews(teamId, creator, status, minScore, maxScore,
                startTime, endTime, page, pageSize);
        List<ReviewListItemVO> items = records.getRecords().stream()
                .map(this::toListItemVO)
                .toList();
        return PageResult.of(items, records.getTotal(), page, pageSize);
    }

    /**
     * 查询审查详情
     */
    public Optional<ReviewRecord> getReviewById(String id) {
        return Optional.ofNullable(reviewRecordMapper.findByIdAndNotDeleted(parseUuid(id)));
    }

    /**
     * 查询审查详情并附带历史重评记录。
     *
     * <p>详情页需要展示当前评分结果、AI 建议、证据字段和历史快照，所以这里会额外查询
     * review_history 表。</p>
     */
    public ReviewDetailVO getReviewDetail(String id) {
        ReviewRecord record = getReviewById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到审查记录: " + id));

        List<ReviewHistory> historyList = reviewHistoryMapper.selectList(
                new LambdaQueryWrapper<ReviewHistory>()
                        .eq(ReviewHistory::getReviewId, parseUuid(id))
                        .orderByDesc(ReviewHistory::getCreatedAt)
        );

        return toDetailVO(record, historyList);
    }

    /**
     * 重新审查并返回列表项格式。
     *
     * <p>Controller 只关心前端展示结构，所以这里把实体转换为 VO。</p>
     */
    public ReviewListItemVO retriggerReviewItem(String requirementId, String reason, String operator) {
        return toListItemVO(retriggerReview(requirementId, reason, operator));
    }

    public ReviewListItemVO retriggerReviewItem(String requirementId, String reason) {
        return retriggerReviewItem(requirementId, reason, RETRIGGER_OPERATOR);
    }

    /**
     * 人工更新审查状态。
     *
     * <p>AI 未完成评分时不能更新人工状态，避免用户把“评分中”的半成品结果误判为已处理。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public ReviewRecord updateReviewStatus(String reviewId, String status, String operator) {
        ReviewRecord record = reviewRecordMapper.findByIdAndNotDeleted(parseUuid(reviewId));
        if (record == null) {
            throw new IllegalArgumentException("未找到审查记录: " + reviewId);
        }
        if (ReviewStatus.REVIEWING.getCode().equals(record.getStatus())) {
            throw new IllegalArgumentException("AI 正在评分，暂不能更新状态");
        }
        if (!MANUAL_REVIEW_STATUSES.contains(status)) {
            throw new IllegalArgumentException("不支持的审查状态: " + status);
        }
        if (record.getTotalScore() == null) {
            throw new IllegalArgumentException("AI 未完成评分，暂不能更新状态");
        }
        record.setStatus(status);
        record.setReviewedBy(operator);
        reviewRecordMapper.updateById(record);

        auditLogService.log(operator, "REVIEW_STATUS_UPDATED", "review", reviewId,
                MapUtil.of("status", status));

        return record;
    }

    /**
     * 更新审查状态并返回列表项格式。
     */
    public ReviewListItemVO updateReviewStatusItem(String reviewId, String status, String operator) {
        return toListItemVO(updateReviewStatus(reviewId, status, operator));
    }

    public ReviewListItemVO updateReviewStatusItem(String reviewId, String status) {
        return updateReviewStatusItem(reviewId, status, DEFAULT_OPERATOR);
    }

    /**
     * 人工恢复失败的 AI 审查工作流。
     *
     * <p>断点服务负责判断当前记录是否允许恢复，并递增恢复次数；工作流第一版采用完整重跑。</p>
     */
    public ReviewListItemVO resumeWorkflow(String reviewId) {
        UUID parsedReviewId = parseUuid(reviewId);
        ReviewRecord record = reviewRecordMapper.findByIdAndNotDeleted(parsedReviewId);
        if (record == null) {
            throw new IllegalArgumentException("未找到审查记录: " + reviewId);
        }

        ReviewWorkflowCheckpoint checkpoint = workflowCheckpointService.startResume(parsedReviewId);
        ReviewWorkflowResult result = null;
        try {
            result = reviewWorkflow.resume(
                    ReviewWorkflowRequest.from(record, checkpoint.getWorkflowInstanceId()),
                    checkpoint.getCurrentNode(),
                    checkpoint.getStateSnapshot()
            );
            applyWorkflowResult(record, result);
            reviewRecordMapper.updateById(record);
        } catch (RuntimeException e) {
            if (result != null) {
                markWorkflowResultFailed(checkpoint.getWorkflowInstanceId(), result, e);
            } else {
                markResumeFailed(checkpoint, e);
            }
            throw e;
        }

        try {
            workflowCheckpointService.markCompleted(
                    checkpoint.getWorkflowInstanceId(),
                    buildWorkflowResultSnapshot(result)
            );
        } catch (Exception e) {
            log.error("标记 AI 评分工作流恢复断点完成失败: reviewId={}, workflowInstanceId={}",
                    parsedReviewId, checkpoint.getWorkflowInstanceId(), e);
        }
        try {
            auditLogService.log("system", "REVIEW_WORKFLOW_RESUMED", "review", record.getId().toString(),
                    MapUtil.<String, Object>builder("requirementId", record.getRequirementId())
                            .put("workflowInstanceId", checkpoint.getWorkflowInstanceId())
                            .build());
        } catch (Exception e) {
            log.warn("记录 AI 评分工作流恢复审计日志失败: reviewId={}", parsedReviewId, e);
        }

        return toListItemVO(record);
    }

    private void markResumeFailed(ReviewWorkflowCheckpoint checkpoint, RuntimeException exception) {
        try {
            workflowCheckpointService.markFailed(
                    checkpoint.getWorkflowInstanceId(),
                    ReviewWorkflowNode.fromCode(checkpoint.getCurrentNode()),
                    checkpoint.getStateSnapshot(),
                    exception
            );
        } catch (RuntimeException checkpointFailure) {
            exception.addSuppressed(checkpointFailure);
            log.error("标记 AI 评分工作流恢复失败断点失败: workflowInstanceId={}",
                    checkpoint.getWorkflowInstanceId(), checkpointFailure);
        }
    }

    private void markWorkflowResultFailed(String workflowInstanceId, ReviewWorkflowResult result, Exception exception) {
        try {
            workflowCheckpointService.markFailed(
                    workflowInstanceId,
                    ReviewWorkflowNode.WAIT_FOR_HUMAN_CONFIRMATION,
                    buildWorkflowResultSnapshot(result),
                    exception
            );
        } catch (RuntimeException checkpointFailure) {
            exception.addSuppressed(checkpointFailure);
            log.error("标记 AI 评分工作流结果落库失败断点失败: workflowInstanceId={}",
                    workflowInstanceId, checkpointFailure);
        }
    }

    /**
     * 查询团队维度的基础统计。
     *
     * <p>通过率这里返回百分数，后续对外 VO 是否按 0 到 1 展示由具体接口转换。</p>
     */
    public TeamStatsData getTeamStats(String teamId, LocalDateTime startTime, LocalDateTime endTime) {
        Double avgScore = reviewRecordMapper.avgScoreByTeam(teamId, startTime, endTime);
        Long total = reviewRecordMapper.countByTeam(teamId, startTime, endTime);
        Long approved = reviewRecordMapper.countApprovedByTeam(teamId, startTime, endTime);
        Long pending = reviewRecordMapper.countPendingByTeam(teamId);

        double passRate = total > 0 ? (double) approved / total * 100 : 0;

        return new TeamStatsData(avgScore, passRate, pending, total);
    }

    /**
     * 查询团队统计出参。
     *
     * <p>未传时间范围时默认统计最近 30 天。</p>
     */
    public TeamStatsVO getTeamStatsVO(String teamId, LocalDateTime startTime, LocalDateTime endTime) {
        LocalDateTime resolvedStartTime = startTime != null ? startTime : LocalDateTime.now().minusDays(30);
        LocalDateTime resolvedEndTime = endTime != null ? endTime : LocalDateTime.now();
        TeamStatsData data = getTeamStats(teamId, resolvedStartTime, resolvedEndTime);

        return new TeamStatsVO(
                data.avgScore(),
                data.passRate(),
                data.pendingCount(),
                data.totalReviewed(),
                Map.of()
        );
    }

    /**
     * 查询看板总览统计。
     *
     * <p>总览数据来自 Mapper 聚合查询，Service 负责把数据库返回的 Map 转成稳定的 VO 结构。</p>
     */
    public StatsOverviewVO getOverviewStatsVO(String teamId, LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> aggregate = Optional
                .ofNullable(reviewRecordMapper.selectOverviewAggregate(teamId, startTime, endTime))
                .orElse(Map.of());

        long totalReviewed = getLongValue(aggregate, "totalReviewed");
        long approvedCount = getLongValue(aggregate, "approvedCount");

        // 前端看板按 0~1 展示合格率，渲染时再转换成百分比。
        List<StatsOverviewVO.TeamRanking> teamRanking = reviewRecordMapper
                .selectTeamRanking(teamId, startTime, endTime)
                .stream()
                .map(row -> {
                    long reviewCount = getLongValue(row, "reviewCount");
                    long teamApprovedCount = getLongValue(row, "approvedCount");
                    return new StatsOverviewVO.TeamRanking(
                            getStringValue(row, "teamId"),
                            getStringValue(row, "teamName"),
                            getDoubleValue(row, "avgScore"),
                            reviewCount,
                            calculateRate(teamApprovedCount, reviewCount)
                    );
                })
                .toList();

        return new StatsOverviewVO(
                getDoubleValue(aggregate, "avgScore"),
                calculateRate(approvedCount, totalReviewed),
                getLongValue(aggregate, "pendingCount"),
                totalReviewed,
                new StatsOverviewVO.ScoreDistribution(
                        getLongValue(aggregate, "excellent"),
                        getLongValue(aggregate, "good"),
                        getLongValue(aggregate, "warning"),
                        getLongValue(aggregate, "fail")
                ),
                teamRanking
        );
    }

    /**
     * 查询评分趋势数据。
     *
     * <p>Mapper 已经过滤掉未出分记录，这里只按日期聚合每天的平均分和审查数量。</p>
     */
    public List<Map<String, Object>> getTrendData(String teamId, LocalDateTime startTime, LocalDateTime endTime) {
        List<ReviewRecord> records = reviewRecordMapper.findForTrend(teamId, startTime, endTime);

        Map<String, List<Integer>> dateScoreMap = MapUtil.newHashMap(true);

        for (ReviewRecord record : records) {
            String dateKey = record.getCreatedAt().toLocalDate().toString();
            dateScoreMap.computeIfAbsent(dateKey, k -> new ArrayList<>()).add(record.getTotalScore());
        }

        List<Map<String, Object>> trends = CollUtil.newArrayList();
        for (Map.Entry<String, List<Integer>> entry : dateScoreMap.entrySet()) {
            double avgScore = entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
            Map<String, Object> point = MapUtil.newHashMap(3);
            point.put("date", entry.getKey());
            point.put("avgScore", Math.round(avgScore * 100.0) / 100.0);
            point.put("reviewCount", entry.getValue().size());
            trends.add(point);
        }

        return trends;
    }

    /**
     * 根据前端传入的周期计算趋势查询时间范围。
     */
    public List<Map<String, Object>> getTrendStats(String teamId, String period) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime = switch (period) {
            case "month" -> now.minusDays(30);
            case "quarter" -> now.minusDays(90);
            default -> now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        };

        return getTrendData(teamId, startTime, now);
    }

    /**
     * 计算比例，分母为 0 时返回 0，避免看板出现除零异常。
     */
    private static double calculateRate(long numerator, long denominator) {
        return denominator > 0 ? (double) numerator / denominator : 0;
    }

    /**
     * 从 Mapper 聚合结果中安全读取 long。
     *
     * <p>不同 JDBC 驱动可能返回 Number 或字符串，这里统一兼容。</p>
     */
    private static long getLongValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            return Long.parseLong(text);
        }
        return 0;
    }

    /**
     * 从 Mapper 聚合结果中安全读取 double。
     */
    private static double getDoubleValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            return Double.parseDouble(text);
        }
        return 0;
    }

    /**
     * 从 Mapper 聚合结果中安全读取字符串。
     */
    private static String getStringValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value.toString() : "";
    }

    /**
     * 将接口传入的字符串 ID 转成 UUID。
     *
     * <p>统一在 Service 层转换，可以让 Controller 保持简单，并返回稳定的参数错误文案。</p>
     */
    private static UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的审查记录 ID: " + id, e);
        }
    }

    private void applyWorkflowResult(ReviewRecord record, ReviewWorkflowResult result) {
        record.setTotalScore(result.totalScore());
        record.setLevel(result.level());
        record.setDimensionScores(result.dimensionScores());
        record.setAiSuggestions(result.aiSuggestions());
        record.setImprovementSuggestion(result.improvementSuggestion());
        record.setStatus(ReviewStatus.PENDING.getCode());
        record.setAiModelUsed(result.aiModelUsed());
        record.setAiLatencyMs(result.aiLatencyMs());
        record.setRawPrompt(result.rawPrompt());
        record.setRawAiResponse(result.rawAiResponse());
        record.setCompletedAt(LocalDateTime.now());
    }

    private Map<String, Object> buildWorkflowResultSnapshot(ReviewWorkflowResult result) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("totalScore", result.totalScore());
        snapshot.put("level", result.level());
        snapshot.put("dimensionScores", result.dimensionScores());
        snapshot.put("aiSuggestions", result.aiSuggestions());
        snapshot.put("aiModelUsed", result.aiModelUsed());
        snapshot.put("aiLatencyMs", result.aiLatencyMs());
        return snapshot;
    }

    /**
     * 将数据库实体转换为列表项。
     */
    private ReviewListItemVO toListItemVO(ReviewRecord record) {
        return new ReviewListItemVO(
                record.getId().toString(),
                record.getRequirementId(),
                record.getRequirementTitle(),
                record.getRequirementDescription(),
                record.getPriority(),
                record.getTeamId(),
                record.getCreator(),
                record.getTotalScore(),
                record.getDimensionScores(),
                record.getLevel(),
                record.getStatus(),
                record.getReviewedBy(),
                record.getCreatedAt(),
                record.getCompletedAt()
        );
    }

    /**
     * 将数据库实体转换为详情页出参。
     *
     * <p>新版本 AI 建议是结构化对象，旧版本可能只是字符串列表；
     * 这里统一转换为前端能稳定读取的 DimensionDetail。</p>
     */
    @SuppressWarnings("unchecked")
    private ReviewDetailVO toDetailVO(ReviewRecord record, List<ReviewHistory> historyList) {
        Map<String, ReviewDetailVO.DimensionDetail> dimensions = new LinkedHashMap<>();
        for (ReviewDimension dim : ReviewDimension.values()) {
            Integer score = record.getDimensionScores() != null ? record.getDimensionScores().get(dim.getCode()) : null;
            DimensionReviewData reviewData = extractDimensionReviewData(record.getAiSuggestions(), dim.getCode());
            dimensions.put(dim.getCode(), new ReviewDetailVO.DimensionDetail(
                    score,
                    dim.getDefaultWeight(),
                    reviewData.suggestions(),
                    reviewData.evidence(),
                    reviewData.missingItems(),
                    reviewData.scoreReason(),
                    reviewData.confidence()
            ));
        }

        List<ReviewDetailVO.ReviewHistoryVO> historyVOs = CollUtil.isEmpty(historyList)
                ? List.of()
                : historyList.stream()
                .map(h -> new ReviewDetailVO.ReviewHistoryVO(
                        h.getTotalScore(),
                        h.getAiModelUsed(),
                        h.getReason(),
                        h.getCreatedAt()
                ))
                .toList();

        return new ReviewDetailVO(
                record.getId().toString(),
                record.getRequirementId(),
                record.getRequirementTitle(),
                record.getRequirementDescription(),
                record.getPriority(),
                record.getRequirementUrl(),
                record.getTeamId(),
                null,
                record.getCreator(),
                record.getTotalScore(),
                record.getLevel(),
                record.getImprovementSuggestion() != null ? record.getImprovementSuggestion() : "",
                record.getImprovementSuggestion(),
                record.getStatus(),
                record.getReviewedBy(),
                record.getAiModelUsed(),
                record.getAiLatencyMs(),
                record.getRetriggerCount(),
                dimensions,
                historyVOs,
                record.getCreatedAt(),
                record.getCompletedAt()
        );
    }

    /**
     * 提取某个评分维度的 AI 反馈。
     *
     * <p>兼容两种历史数据：旧数据是建议列表，新数据是包含 suggestions、evidence、
     * missing_items、score_reason、confidence 的结构化对象。</p>
     */
    private DimensionReviewData extractDimensionReviewData(Map<String, Object> aiSuggestions, String dimensionCode) {
        if (aiSuggestions == null) {
            return DimensionReviewData.empty();
        }
        Object value = aiSuggestions.get(dimensionCode);
        if (value instanceof Map<?, ?> reviewMap) {
            return new DimensionReviewData(
                    toStringList(reviewMap.get("suggestions")),
                    toStringList(reviewMap.get("evidence")),
                    toStringList(firstNonNull(reviewMap.get("missing_items"), reviewMap.get("missingItems"))),
                    toStringValue(firstNonNull(reviewMap.get("score_reason"), reviewMap.get("scoreReason"))),
                    toDoubleValue(reviewMap.get("confidence"))
            );
        }
        return new DimensionReviewData(toStringList(value), List.of(), List.of(), "", null);
    }

    /**
     * 返回第一个非空对象，主要用于兼容下划线和驼峰两种字段名。
     */
    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    /**
     * 把 JSON 反序列化后的任意值转换为字符串列表。
     */
    private static List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null && StrUtil.isNotBlank(item.toString())) {
                    values.add(item.toString());
                }
            }
            return values;
        }
        if (value instanceof CharSequence text && StrUtil.isNotBlank(text)) {
            return List.of(text.toString());
        }
        return List.of();
    }

    /**
     * 把任意对象转换为空安全字符串。
     */
    private static String toStringValue(Object value) {
        return value != null ? value.toString() : "";
    }

    /**
     * 把任意对象转换为 Double，无法转换时返回 null。
     */
    private static Double toDoubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 单个维度的结构化 AI 反馈。
     */
    private record DimensionReviewData(
            List<String> suggestions,
            List<String> evidence,
            List<String> missingItems,
            String scoreReason,
            Double confidence
    ) {
        static DimensionReviewData empty() {
            return new DimensionReviewData(List.of(), List.of(), List.of(), "", null);
        }
    }

    /**
     * 保存重新审查前的旧评分快照。
     *
     * <p>只有已经产生过评分的记录才会写历史；首次评分失败或评分中的记录没有可保存快照。</p>
     */
    private void saveHistorySnapshot(ReviewRecord record, String reason) {
        if (record.getTotalScore() == null || record.getDimensionScores() == null) {
            return;
        }
        ReviewHistory history = ReviewHistory.builder()
                .reviewId(record.getId())
                .totalScore(record.getTotalScore())
                .dimensionScores(record.getDimensionScores())
                .aiSuggestions(record.getAiSuggestions())
                .aiModelUsed(record.getAiModelUsed())
                .aiLatencyMs(record.getAiLatencyMs())
                .reason(reason)
                .build();
        reviewHistoryMapper.insert(history);
    }

    /**
     * 等当前事务提交后再启动后台评分。
     *
     * <p>这样后台线程读取记录时，一定能看到刚插入或刚更新的数据库状态。</p>
     */
    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(task);
                }
            });
            return;
        }
        CompletableFuture.runAsync(task);
    }

    /**
     * 缓存审查结果标记。
     *
     * <p>当前缓存值只保存记录 ID，用于后续扩展快速检查或刷新，不承载完整评分数据。</p>
     */
    private void cacheReviewResult(ReviewRecord record) {
        try {
            String key = "hexascope:review:" + record.getId();
            redisTemplate.opsForValue().set(key, record.getId().toString(), CACHE_TTL);
        } catch (Exception e) {
            log.warn("缓存审查结果失败", e);
        }
    }

    /**
     * 重新审查限流异常。
     *
     * <p>消息中已携带重试倒计时，全局异常处理器直接透传 message 返回给前端。</p>
     */
    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) {
            super(message);
        }
    }

    /**
     * 团队统计的内部中间结果。
     */
    public record TeamStatsData(
            Double avgScore,
            Double passRate,
            Long pendingCount,
            Long totalReviewed
    ) {
    }
}
