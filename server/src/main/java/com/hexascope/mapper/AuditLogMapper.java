/*
 * 文件说明：MyBatis 数据访问接口，封装业务表的增删改查入口。
 */
package com.hexascope.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hexascope.model.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志 Mapper
 *
 * @author Hexascope Team
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
