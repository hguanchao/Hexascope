/*
 * 文件说明：后端接口控制器，负责接收请求参数并委托 Service 返回统一结果。
 */
package com.hexascope.controller;

import com.hexascope.common.PageResult;
import com.hexascope.common.Result;
import com.hexascope.model.dto.CreateRequirementRequest;
import com.hexascope.model.dto.RetriggerRequest;
import com.hexascope.model.dto.ReviewStatusUpdateRequest;
import com.hexascope.model.vo.ReviewDetailVO;
import com.hexascope.model.vo.ReviewListItemVO;
import com.hexascope.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 需求列表接口层
 *
 * @author Hexascope Team
 */
@RestController
@RequestMapping("/requirements")
@RequiredArgsConstructor
public class RequirementController {

    private final ReviewService reviewService;

    /**
     * 创建需求并异步触发 AI 评分。
     *
     * <p>接口会先保存需求记录并返回“评分中”状态，真正的 AI 评分在事务提交后后台执行，
     * 避免前端创建需求时被大模型耗时阻塞。</p>
     */
    @PostMapping
    public Result<ReviewListItemVO> createRequirement(@Valid @RequestBody CreateRequirementRequest request) {
        return Result.success(reviewService.createRequirementWithReview(
                request.requirementId(),
                request.title(),
                request.description(),
                request.priority(),
                request.creator(),
                request.workspaceId(),
                request.teamId(),
                request.kbFilters()
        ));
    }

    /**
     * 分页查询需求审查列表。
     *
     * <p>支持按团队、创建人、状态、分数区间和创建时间过滤。
     * 排序规则由 Mapper 查询语句统一控制。</p>
     */
    @GetMapping
    public Result<PageResult<ReviewListItemVO>> getRequirementList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) Integer maxScore,
            @RequestParam(required = false) String teamId,
            @RequestParam(required = false) String creator,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return Result.success(reviewService.getReviewPage(teamId, creator, status, minScore, maxScore,
                startTime, endTime, page, pageSize));
    }

    /**
     * 查询单条需求的完整审查报告。
     *
     * <p>详情包含原始需求、总分、维度分、AI 建议、证据字段和历史重评记录。</p>
     */
    @GetMapping("/{id}")
    public Result<ReviewDetailVO> getRequirementDetail(@PathVariable String id) {
        return Result.success(reviewService.getReviewDetail(id));
    }

    /**
     * 重新触发某个需求的 AI 审查。
     *
     * <p>服务层会做冷却时间校验，并在重评前保存旧评分快照。</p>
     */
    @PostMapping("/retrigger")
    public Result<ReviewListItemVO> retriggerRequirement(@Valid @RequestBody RetriggerRequest request) {
        return Result.success(reviewService.retriggerReviewItem(request.requirementId(), request.reason()));
    }

    /**
     * 人工更新审查状态。
     *
     * <p>只允许已完成 AI 评分的记录进入人工通过、拒绝或需修改等状态。</p>
     */
    @PostMapping("/{id}/status")
    public Result<ReviewListItemVO> updateRequirementStatus(@PathVariable String id,
                                                            @Valid @RequestBody ReviewStatusUpdateRequest request) {
        return Result.success(reviewService.updateReviewStatusItem(id, request.status()));
    }

    /**
     * 人工恢复失败的 AI 审查工作流。
     *
     * <p>只有断点处于失败状态时服务层才会真正重入；已完成或等待人工确认的工作流不会重复执行。</p>
     */
    @PostMapping("/{id}/workflow/resume")
    public Result<ReviewListItemVO> resumeWorkflow(@PathVariable String id) {
        return Result.success(reviewService.resumeWorkflow(id));
    }
}
