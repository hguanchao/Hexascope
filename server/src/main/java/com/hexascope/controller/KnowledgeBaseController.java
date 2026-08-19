/*
 * 文件说明：后端接口控制器，负责接收请求参数并委托 Service 返回统一结果。
 */
package com.hexascope.controller;

import com.hexascope.common.PageResult;
import com.hexascope.common.Result;
import com.hexascope.model.dto.CreateEvalCaseRequest;
import com.hexascope.model.dto.RunEvalRequest;
import com.hexascope.model.vo.KbEvalCaseVO;
import com.hexascope.model.vo.KbEvalRunVO;
import com.hexascope.model.vo.KbEvalSummaryVO;
import com.hexascope.model.vo.KnowledgeDocumentVO;
import com.hexascope.model.vo.KnowledgeSourceVO;
import com.hexascope.model.vo.KnowledgeTraceVO;
import com.hexascope.service.KbEvalService;
import com.hexascope.service.KnowledgeTraceService;
import com.hexascope.service.ScoringRubricService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口层
 *
 * @author Hexascope Team
 */
@RestController
@RequestMapping("/knowledge-base")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final ScoringRubricService scoringRubricService;
    private final KnowledgeTraceService knowledgeTraceService;
    private final KbEvalService kbEvalService;

    /**
     * 查询评分标准知识库统计。
     *
     * <p>用于前端展示当前向量库里有多少评分细则、扣分项和来源类型。</p>
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        return Result.success(scoringRubricService.getKnowledgeStats());
    }

    /**
     * 分页查询知识源（版本历史）。
     *
     * <p>每个知识源对应一次 Excel 导入链，版本号随文件内容变化递增。</p>
     */
    @GetMapping("/sources")
    public Result<PageResult<KnowledgeSourceVO>> getSources(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(scoringRubricService.getKnowledgeSources(page, pageSize));
    }

    /**
     * 分页查询向量库中的评分标准片段。
     *
     * <p>这里查的是已经入库的知识片段，不重新生成向量；主要用于导入后人工核对。</p>
     */
    @GetMapping("/documents")
    public Result<PageResult<KnowledgeDocumentVO>> getDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source) {
        return Result.success(scoringRubricService.getKnowledgeDocuments(page, pageSize, keyword, source));
    }

    /**
     * 导入评分标准 Excel。
     *
     * <p>服务层会把 Excel 中的评分细则和扣分项转换为向量文档，后续 AI 评分时通过 RAG 检索。</p>
     */
    @PostMapping("/import")
    public Result<Map<String, Object>> importScoringRubric(@RequestParam("file") MultipartFile file) {
        return Result.success(scoringRubricService.importExcelResult(file));
    }

    /**
     * 删除单个知识库片段。
     *
     * <p>删除后该片段不会再参与后续评分标准召回。</p>
     */
    @DeleteMapping("/documents/{id}")
    public Result<Map<String, Object>> deleteDocument(@PathVariable String id) {
        return Result.success(scoringRubricService.deleteKnowledgeDocument(id));
    }

    /**
     * 分页查询检索追踪记录。
     *
     * <p>每次 AI 评分检索成功都会打点，可传入审查记录 ID 定位某次评分的检索过程。</p>
     */
    @GetMapping("/traces")
    public Result<PageResult<KnowledgeTraceVO>> getTraces(
            @RequestParam(required = false) String reviewId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(knowledgeTraceService.queryByReview(reviewId, page, pageSize));
    }

    /**
     * 清理 N 天前的检索追踪记录。
     */
    @PostMapping("/traces/clean")
    public Result<Map<String, Object>> cleanTraces(@RequestParam(defaultValue = "30") int olderThanDays) {
        int deleted = knowledgeTraceService.clean(olderThanDays);
        return Result.success(Map.of("deleted", deleted));
    }

    /**
     * 分页查询评估用例。
     *
     * <p>导入评分表时按行自动生成自标注用例，也可以人工补充用例。</p>
     */
    @GetMapping("/eval/cases")
    public Result<PageResult<KbEvalCaseVO>> getEvalCases(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String dimension) {
        return Result.success(kbEvalService.listCases(page, pageSize, dimension));
    }

    /**
     * 创建人工补充的评估用例。
     */
    @PostMapping("/eval/cases")
    public Result<Void> createEvalCase(@Valid @RequestBody CreateEvalCaseRequest request) {
        kbEvalService.createCase(request);
        return Result.success(null);
    }

    /**
     * 删除评估用例（关联运行结果级联删除）。
     */
    @DeleteMapping("/eval/cases/{id}")
    public Result<Void> deleteEvalCase(@PathVariable String id) {
        kbEvalService.deleteCase(id);
        return Result.success(null);
    }

    /**
     * 批量运行全部评估用例。
     *
     * <p>mode 支持 vector（纯向量）/ hybrid（混合）/ all（两种都跑），
     * 同一用例在两种配置下的指标可对比出混合检索的召回收益。</p>
     */
    @PostMapping("/eval/run-all")
    public Result<Map<String, Integer>> runAllEval(@RequestBody(required = false) RunEvalRequest request) {
        int executed = kbEvalService.runAll(request);
        return Result.success(Map.of("executed", executed));
    }

    /**
     * 分页查询评估运行结果（可按用例过滤）。
     */
    @GetMapping("/eval/runs")
    public Result<PageResult<KbEvalRunVO>> getEvalRuns(
            @RequestParam(required = false) String caseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(kbEvalService.listRuns(caseId, page, pageSize));
    }

    /**
     * 评估汇总对比：每个用例最近一次向量与混合运行的指标并排返回。
     */
    @GetMapping("/eval/summary")
    public Result<List<KbEvalSummaryVO>> getEvalSummary(
            @RequestParam(required = false) String dimension,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(kbEvalService.getSummary(dimension, limit));
    }
}
