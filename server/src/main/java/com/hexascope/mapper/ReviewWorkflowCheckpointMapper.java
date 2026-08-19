/*
 * 文件说明：MyBatis 数据访问接口，封装业务表的增删改查入口。
 */
package com.hexascope.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hexascope.model.entity.ReviewWorkflowCheckpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;
import java.util.UUID;

/**
 * AI 审查工作流断点 Mapper
 *
 * @author Hexascope Team
 */
@Mapper
public interface ReviewWorkflowCheckpointMapper extends BaseMapper<ReviewWorkflowCheckpoint> {

    ReviewWorkflowCheckpoint findLatestByReviewId(@Param("reviewId") UUID reviewId);

    ReviewWorkflowCheckpoint findByWorkflowInstanceId(@Param("workflowInstanceId") String workflowInstanceId);

    int updateNodeState(@Param("workflowInstanceId") String workflowInstanceId,
                        @Param("currentNode") String currentNode,
                        @Param("status") String status,
                        @Param("stateSnapshot") Map<String, Object> stateSnapshot,
                        @Param("errorMessage") String errorMessage,
                        @Param("completed") boolean completed);

    int tryStartResume(@Param("id") UUID id,
                       @Param("runningStatus") String runningStatus,
                       @Param("failedStatus") String failedStatus,
                       @Param("maxResumeCount") int maxResumeCount);
}
