/*
 * 文件说明：后端业务服务，集中承载需求评审、配置、审计或外部系统连接逻辑。
 */
package com.hexascope.service;

import cn.hutool.core.util.StrUtil;
import com.hexascope.mapper.AuditLogMapper;
import com.hexascope.model.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 审计日志 Service
 *
 * <p>用于记录关键业务动作，例如创建需求、完成评分、重新审查、更新状态和知识库变更。
 * 这些日志不参与主业务判断，但用于排查操作来源和回放用户行为。</p>
 *
 * @author Hexascope Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 写入一条审计日志。
     *
     * <p>detail 用 JSONB 保存扩展信息，不同业务动作可以写入不同字段，不需要频繁改表结构。
     * 当前暂未接入真实客户端 IP，所以 ipAddress 先写空字符串。</p>
     *
     * @param operator 操作人
     * @param action 操作类型
     * @param targetType 被操作对象类型
     * @param targetId 被操作对象 ID
     * @param detail 操作详情
     */
    @Transactional(rollbackFor = Exception.class)
    public void log(String operator, String action, String targetType,
                    String targetId, Map<String, Object> detail) {
        AuditLog auditLog = AuditLog.builder()
                .operator(operator)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .detail(detail)
                .ipAddress(StrUtil.EMPTY)
                .build();

        auditLogMapper.insert(auditLog);
    }
}
