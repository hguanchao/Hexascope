/*
 * 文件说明：MyBatis 数据访问接口，封装业务表的增删改查入口。
 */
package com.hexascope.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hexascope.model.entity.ReviewRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 审查记录 Mapper
 *
 * @author Hexascope Team
 */
@Mapper
public interface ReviewRecordMapper extends BaseMapper<ReviewRecord> {

    /**
     * 根据需求 ID 查询最新审查记录
     */
    @ResultMap("reviewRecordResultMap")
    @Select("SELECT * FROM review_record WHERE requirement_id = #{requirementId} AND is_deleted = false ORDER BY created_at DESC LIMIT 1")
    ReviewRecord findLatestByRequirementId(@Param("requirementId") String requirementId);

    /**
     * 根据 ID 查询未删除的记录
     */
    @ResultMap("reviewRecordResultMap")
    @Select("SELECT * FROM review_record WHERE id = #{id} AND is_deleted = false")
    ReviewRecord findByIdAndNotDeleted(@Param("id") UUID id);

    /**
     * 多条件分页查询
     */
    IPage<ReviewRecord> pageByConditions(Page<ReviewRecord> page,
                                          @Param("teamId") String teamId,
                                          @Param("creator") String creator,
                                          @Param("status") String status,
                                          @Param("minScore") Integer minScore,
                                          @Param("maxScore") Integer maxScore,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 统计团队平均分。
     *
     * <p>异步评分会先产生无分数记录，这类记录只代表任务状态，
     * 不能参与质量均分计算。</p>
     */
    @Select("""
            SELECT COALESCE(AVG(total_score), 0) FROM review_record
            WHERE is_deleted = false AND team_id = #{teamId}
            AND total_score IS NOT NULL
            AND created_at >= #{startTime} AND created_at <= #{endTime}
            """)
    Double avgScoreByTeam(@Param("teamId") String teamId,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime);

    /**
     * 统计团队已完成评分的审查总数。
     *
     * <p>这里按 total_score 过滤，而不是按状态过滤，避免评分失败或评分中记录
     * 被误算为已审查需求。</p>
     */
    @Select("""
            SELECT COUNT(*) FROM review_record
            WHERE is_deleted = false AND team_id = #{teamId}
            AND total_score IS NOT NULL
            AND created_at >= #{startTime} AND created_at <= #{endTime}
            """)
    Long countByTeam(@Param("teamId") String teamId,
                      @Param("startTime") LocalDateTime startTime,
                      @Param("endTime") LocalDateTime endTime);

    /**
     * 统计团队通过数。
     *
     * <p>只有已出分且人工通过的记录才计入通过率分子。</p>
     */
    @Select("""
            SELECT COUNT(*) FROM review_record
            WHERE is_deleted = false AND team_id = #{teamId}
            AND status = 'approved'
            AND total_score IS NOT NULL
            AND created_at >= #{startTime} AND created_at <= #{endTime}
            """)
    Long countApprovedByTeam(@Param("teamId") String teamId,
                               @Param("startTime") LocalDateTime startTime,
                               @Param("endTime") LocalDateTime endTime);

    /**
     * 统计待审查数
     */
    @Select("SELECT COUNT(*) FROM review_record WHERE is_deleted = false AND team_id = #{teamId} AND status = 'pending'")
    Long countPendingByTeam(@Param("teamId") String teamId);

    /**
     * 查询趋势数据。
     *
     * <p>趋势图只展示真实评分结果，评分中和评分失败记录没有总分，不参与折线计算。</p>
     */
    @ResultMap("reviewRecordResultMap")
    @Select("""
            SELECT * FROM review_record
            WHERE is_deleted = false
            AND (#{teamId}::text IS NULL OR team_id = #{teamId})
            AND total_score IS NOT NULL
            AND created_at >= #{startTime} AND created_at <= #{endTime}
            ORDER BY created_at ASC
            """)
    List<ReviewRecord> findForTrend(@Param("teamId") String teamId,
                                     @Param("startTime") LocalDateTime startTime,
                                     @Param("endTime") LocalDateTime endTime);

    /**
     * 查询看板总览聚合数据。
     *
     * <p>总览口径与趋势、排名保持一致，只聚合已经产生总分的记录。</p>
     */
    @Select("""
            <script>
            SELECT
                COALESCE(ROUND(AVG(total_score)::numeric, 2), 0) AS "avgScore",
                COUNT(*) AS "totalReviewed",
                COALESCE(SUM(CASE WHEN status = 'approved' THEN 1 ELSE 0 END), 0) AS "approvedCount",
                COALESCE(SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END), 0) AS "pendingCount",
                COALESCE(SUM(CASE WHEN total_score &gt;= 85 THEN 1 ELSE 0 END), 0) AS "excellent",
                COALESCE(SUM(CASE WHEN total_score &gt;= 70 AND total_score &lt; 85 THEN 1 ELSE 0 END), 0) AS "good",
                COALESCE(SUM(CASE WHEN total_score &gt;= 55 AND total_score &lt; 70 THEN 1 ELSE 0 END), 0) AS "warning",
                COALESCE(SUM(CASE WHEN total_score &lt; 55 THEN 1 ELSE 0 END), 0) AS "fail"
            FROM review_record
            WHERE is_deleted = false
            AND total_score IS NOT NULL
            <if test="teamId != null and teamId != ''">
                AND team_id = #{teamId}
            </if>
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Map<String, Object> selectOverviewAggregate(@Param("teamId") String teamId,
                                                @Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);

    /**
     * 查询团队质量排名。
     *
     * <p>排名体现已完成审查的需求质量，未完成评分的需求不进入排序。</p>
     */
    @Select("""
            <script>
            SELECT
                r.team_id AS "teamId",
                r.team_id AS "teamName",
                COALESCE(ROUND(AVG(r.total_score)::numeric, 2), 0) AS "avgScore",
                COUNT(*) AS "reviewCount",
                COALESCE(SUM(CASE WHEN r.status = 'approved' THEN 1 ELSE 0 END), 0) AS "approvedCount"
            FROM review_record r
            WHERE r.is_deleted = false
            AND r.total_score IS NOT NULL
            <if test="teamId != null and teamId != ''">
                AND r.team_id = #{teamId}
            </if>
            <if test="startTime != null">
                AND r.created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND r.created_at &lt;= #{endTime}
            </if>
            GROUP BY r.team_id
            ORDER BY AVG(r.total_score) DESC, COUNT(*) DESC
            LIMIT 10
            </script>
            """)
    List<Map<String, Object>> selectTeamRanking(@Param("teamId") String teamId,
                                                @Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);
}
