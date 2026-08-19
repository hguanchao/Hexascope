/*
 * 文件说明：后端接口控制器，负责接收请求参数并委托 Service 返回统一结果。
 */
package com.hexascope.controller;

import com.hexascope.common.Result;
import com.hexascope.model.vo.StatsOverviewVO;
import com.hexascope.model.vo.TeamStatsVO;
import com.hexascope.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 统计数据接口层
 *
 * @author Hexascope Team
 */
@RestController
@RequestMapping("/reviews/stats")
@RequiredArgsConstructor
public class StatsController {

    private final ReviewService reviewService;

    /**
     * 查询团队统计数据
     *
     * @param teamId    团队 ID
     * @param startTime 开始时间（可空）
     * @param endTime   结束时间（可空）
     * @return 团队统计数据
     */
    @GetMapping("/team")
    public Result<TeamStatsVO> getTeamStats(
            @RequestParam String teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return Result.success(reviewService.getTeamStatsVO(teamId, startTime, endTime));
    }

    /**
     * 查询质量趋势数据
     *
     * @param teamId 团队 ID（可空）
     * @param period 时间周期（week/month/quarter），默认 week
     * @return 趋势数据列表 [{date, avgScore, reviewCount}, ...]
     */
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> getTrendStats(
            @RequestParam(required = false) String teamId,
            @RequestParam(defaultValue = "week") String period) {
        return Result.success(reviewService.getTrendStats(teamId, period));
    }

    /**
     * 查询看板总览统计
     *
     * @param teamId    团队 ID（可空）
     * @param startTime 开始时间（可空）
     * @param endTime   结束时间（可空）
     * @return 看板总览统计数据
     */
    @GetMapping("/overview")
    public Result<StatsOverviewVO> getOverviewStats(
            @RequestParam(required = false) String teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return Result.success(reviewService.getOverviewStatsVO(teamId, startTime, endTime));
    }
}
