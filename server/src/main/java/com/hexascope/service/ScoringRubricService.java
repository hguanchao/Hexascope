/*
 * 文件说明：评分标准知识库服务，负责导入和管理向量库中的评分标准文档。
 */
package com.hexascope.service;

import cn.hutool.core.map.MapUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.hexascope.ai.kb.KnowledgeChunker;
import com.hexascope.ai.kb.KnowledgeDiffCalculator;
import com.hexascope.common.PageResult;
import com.hexascope.model.vo.KnowledgeDocumentVO;
import com.hexascope.model.vo.KnowledgeSourceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 评分标准知识库 Service
 *
 * <p>负责从 Excel 文件读取评分标准，分块后通过 Embedding 模型生成向量并写入
 * pgvector 向量库。导入带版本管理：文件指纹去重、内容变化自动升级版本、
 * 归档旧版本快照并计算行级差异。</p>
 *
 * @author Hexascope Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringRubricService {

    private static final String DEFAULT_OPERATOR = "current user";
    private static final String VECTOR_TABLE = "vector_store";
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 向量文档 metadata 键：来源知识源 ID、版本、是否参与检索、内容哈希。
     */
    private static final String META_SOURCE_ID = "source_id";
    private static final String META_SOURCE_VERSION = "source_version";
    private static final String META_SOURCE_ACTIVE = "source_active";
    private static final String META_CONTENT_HASH = "content_hash";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;
    private final KnowledgeChunker knowledgeChunker;
    private final KbEvalService kbEvalService;

    /**
     * 知识库列表查询的行映射器。
     *
     * <p>向量库表里的 metadata 是 JSON 字段，SQL 会先把常用 metadata 提取成列，
     * 再由这里组装成前端展示用的 VO。</p>
     */
    private final RowMapper<KnowledgeDocumentVO> knowledgeDocumentRowMapper = (rs, rowNum) ->
            new KnowledgeDocumentVO(
                    rs.getString("id"),
                    rs.getString("source"),
                    rs.getString("dimension"),
                    rs.getString("level"),
                    rs.getString("severity"),
                    (Integer) rs.getObject("row_number"),
                    rs.getString("content")
            );

    /**
     * 使用默认操作人导入 Excel。
     */
    public Map<String, Object> importExcelResult(MultipartFile file) {
        return importExcelResult(file, DEFAULT_OPERATOR);
    }

    /**
     * 版本化导入 Excel 评分标准。
     *
     * <p>完整链路：
     * 1. 计算文件指纹，同指纹且未失效的直接跳过（去重）；
     * 2. 解析两个固定 sheet，逐行生成行级文档；
     * 3. 行级文档补充来源版本元数据后分块（超长行切成可检索短块）；
     * 4. 内容变化时：归档旧版本行快照、计算行级差异、删除旧文档、写入新文档；
     * 5. knowledge_source 版本 +1，返回导入结果和差异摘要。</p>
     */
    public Map<String, Object> importExcelResult(MultipartFile file, String operator) {
        String fileName = file.getOriginalFilename();
        String fileHash = sha256Hex(file);

        Optional<KnowledgeSourceRow> existing = findSourceByHash(fileHash);
        if (existing.isPresent() && existing.get().active()) {
            long skipped = countDocumentsBySource(existing.get().id().toString());
            auditLogService.log(operator, "EXCEL_IMPORT_SKIPPED", "scoring_rubric",
                    existing.get().id().toString(),
                    Map.of("fileName", fileName, "count", skipped));
            log.info("评分标准未变化，跳过重复导入: fileName={}", fileName);
            return MapUtil.<String, Object>builder("importedCount", 0L)
                    .put("skippedCount", skipped)
                    .put("version", existing.get().version())
                    .put("changed", false)
                    .put("fileName", fileName)
                    .build();
        }

        List<Document> rowDocuments = parseWorkbook(file);
        if (rowDocuments.isEmpty()) {
            log.warn("Excel 中未找到评分标准数据: fileName={}", fileName);
            return MapUtil.<String, Object>builder("importedCount", 0L)
                    .put("skippedCount", 0L)
                    .put("fileName", fileName)
                    .put("changed", false)
                    .build();
        }

        int newVersion = existing.map(KnowledgeSourceRow::version).orElse(0) + 1;
        UUID sourceId = existing.map(KnowledgeSourceRow::id).orElseGet(UUID::randomUUID);
        String sourceIdText = sourceId.toString();

        // 行级文档补版本元数据后分块，未超限的行只补元数据不分块。
        List<Document> documents = chunkDocuments(rowDocuments, sourceIdText, newVersion);

        // 内容变化时归档旧版本并计算差异，然后删除旧文档、写入新文档。
        Map<String, Object> diffSummary = new LinkedHashMap<>();
        if (existing.isPresent()) {
            List<KnowledgeDiffCalculator.RowSnapshot> oldRows = loadOldRows(sourceIdText);
            List<KnowledgeDiffCalculator.RowSnapshot> newRows = toRowSnapshots(rowDocuments);
            KnowledgeDiffCalculator.DiffResult diff = KnowledgeDiffCalculator.diff(oldRows, newRows);
            saveSnapshot(existing.get(), oldRows, diff);
            diffSummary.put("added", diff.added());
            diffSummary.put("removed", diff.removed());
            diffSummary.put("changed", diff.changed());
            deleteOldDocuments(sourceIdText);
            log.info("评分标准内容变化: version {} -> {}, diff: added={}, removed={}, changed={}",
                    existing.get().version(), newVersion, diff.added().size(),
                    diff.removed().size(), diff.changed().size());
        }

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
        upsertKnowledgeSource(sourceId, fileName, fileHash, newVersion, documents.size());

        // 按行自动生成/更新自标注评估用例（弱监督金标）；失败不阻断导入。
        try {
            kbEvalService.upsertAutoCases(sourceIdText, rowDocuments);
        } catch (Exception e) {
            log.warn("自动生成评估用例失败，不影响导入: {}", e.getMessage());
        }

        auditLogService.log(operator, "EXCEL_IMPORTED", "scoring_rubric",
                sourceIdText, Map.of("fileName", fileName, "version", newVersion,
                        "count", documents.size()));

        log.info("评分标准导入完成: fileName={}, documents={}, version={}", fileName, documents.size(), newVersion);
        return MapUtil.<String, Object>builder("importedCount", documents.size())
                .put("skippedCount", 0L)
                .put("version", newVersion)
                .put("changed", true)
                .put("fileName", fileName)
                .put("diffSummary", diffSummary)
                .build();
    }

    /**
     * 计算上传文件的 SHA-256 指纹，用于重复导入识别。
     */
    private static String sha256Hex(MultipartFile file) {
        try {
            return DigestUtil.sha256Hex(file.getInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败", e);
        }
    }

    /**
     * 查询文件指纹对应的知识源（取最新版本）。
     */
    private Optional<KnowledgeSourceRow> findSourceByHash(String fileHash) {
        List<KnowledgeSourceRow> rows = jdbcTemplate.query(
                "SELECT id, file_name, file_hash, version, active FROM knowledge_source"
                        + " WHERE file_hash = ? ORDER BY version DESC",
                (rs, rowNum) -> new KnowledgeSourceRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("file_name"),
                        rs.getString("file_hash"),
                        rs.getInt("version"),
                        rs.getBoolean("active")),
                fileHash);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 统计当前库中某个知识源的文档数量。
     */
    private long countDocumentsBySource(String sourceIdText) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + VECTOR_TABLE + " WHERE metadata ->> '" + META_SOURCE_ID + "' = ?",
                Long.class, sourceIdText);
        return count == null ? 0 : count;
    }

    /**
     * 解析 Excel，返回行级文档列表（尚未分块、尚未补版本元数据）。
     */
    private List<Document> parseWorkbook(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            List<Document> documents = new ArrayList<>();
            // “评分细则”提供每个维度的分值锚点，是 AI 评分时最主要的参考标准。
            Sheet detailSheet = workbook.getSheet("评分细则");
            if (detailSheet != null) {
                documents.addAll(parseDetailSheet(detailSheet));
            }
            // “扣分项参考”提供常见问题和扣分口径，用来补充模型的负面判断依据。
            Sheet penaltySheet = workbook.getSheet("扣分项参考");
            if (penaltySheet != null) {
                documents.addAll(parsePenaltySheet(penaltySheet));
            }
            return documents;
        } catch (Exception e) {
            log.error("导入评分标准 Excel 失败", e);
            throw new IllegalStateException("导入评分标准失败，请检查文件格式", e);
        }
    }

    /**
     * 行级文档补知识源版本元数据后统一分块。
     */
    private List<Document> chunkDocuments(List<Document> rowDocuments, String sourceIdText, int version) {
        List<Document> documents = new ArrayList<>();
        for (Document rowDocument : rowDocuments) {
            Map<String, Object> metadata = new HashMap<>(rowDocument.getMetadata());
            metadata.put(META_SOURCE_ID, sourceIdText);
            metadata.put(META_SOURCE_VERSION, version);
            metadata.put(META_SOURCE_ACTIVE, "true");
            documents.addAll(knowledgeChunker.chunk(
                    new Document(rowDocument.getText(), metadata)));
        }
        return documents;
    }

    /**
     * 读取旧版本的所有行快照（向量库删除前归档）。
     */
    private List<KnowledgeDiffCalculator.RowSnapshot> loadOldRows(String sourceIdText) {
        return jdbcTemplate.query(
                "SELECT metadata ->> 'source' AS source,"
                        + " COALESCE(NULLIF(metadata ->> 'row', '')::int, 0) AS row_num,"
                        + " metadata ->> '" + META_CONTENT_HASH + "' AS content_hash"
                        + " FROM " + VECTOR_TABLE
                        + " WHERE metadata ->> '" + META_SOURCE_ID + "' = ?",
                (rs, rowNum) -> new KnowledgeDiffCalculator.RowSnapshot(
                        rs.getString("source"),
                        rs.getInt("row_num"),
                        rs.getString("content_hash")),
                sourceIdText);
    }

    /**
     * 把新版本行文档转成行快照（用原始行文本哈希，分块前计算保证跨版本可比）。
     */
    private static List<KnowledgeDiffCalculator.RowSnapshot> toRowSnapshots(List<Document> rowDocuments) {
        List<KnowledgeDiffCalculator.RowSnapshot> snapshots = new ArrayList<>();
        for (Document document : rowDocuments) {
            String source = String.valueOf(document.getMetadata().get("source"));
            Object rowValue = document.getMetadata().get("row");
            int row = rowValue instanceof Number number ? number.intValue() : 0;
            String text = document.getText() != null ? document.getText() : "";
            snapshots.add(new KnowledgeDiffCalculator.RowSnapshot(
                    source, row, DigestUtil.sha256Hex(text)));
        }
        return snapshots;
    }

    /**
     * 归档旧版本行内容与差异摘要，供版本历史展示。
     */
    private void saveSnapshot(KnowledgeSourceRow oldSource, List<KnowledgeDiffCalculator.RowSnapshot> oldRows,
                              KnowledgeDiffCalculator.DiffResult diff) {
        List<Map<String, Object>> rowsJson = oldRows.stream()
                .map(row -> MapUtil.<String, Object>builder("source", row.source())
                        .put("row", row.row())
                        .put("content_hash", row.contentHash())
                        .build())
                .toList();
        Map<String, Object> diffJson = MapUtil.<String, Object>builder("added", diff.added())
                .put("removed", diff.removed())
                .put("changed", diff.changed())
                .build();
        jdbcTemplate.update(
                "INSERT INTO knowledge_source_snapshot (source_id, version, rows, diff_summary)"
                        + " VALUES (?, ?, ?::jsonb, ?::jsonb)",
                oldSource.id(), oldSource.version(),
                JSONUtil.toJsonStr(rowsJson), JSONUtil.toJsonStr(diffJson));
    }

    /**
     * 删除旧版本的全部向量文档（按知识源 ID 过滤查询后再按 ID 删除）。
     */
    private void deleteOldDocuments(String sourceIdText) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id::text FROM " + VECTOR_TABLE + " WHERE metadata ->> '" + META_SOURCE_ID + "' = ?",
                String.class, sourceIdText);
        if (ids.isEmpty()) {
            return;
        }
        vectorStore.delete(ids);
        log.info("已删除旧版本知识文档: sourceId={}, count={}", sourceIdText, ids.size());
    }

    /**
     * 新建或更新知识源（版本 +1 或首次导入）。
     */
    private void upsertKnowledgeSource(UUID sourceId, String fileName, String fileHash,
                                       int version, int importedCount) {
        jdbcTemplate.update(
                "INSERT INTO knowledge_source (id, file_name, file_hash, version, active, imported_count, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, TRUE, ?, NOW(), NOW())"
                        + " ON CONFLICT (id) DO UPDATE SET file_name = EXCLUDED.file_name,"
                        + " file_hash = EXCLUDED.file_hash, version = EXCLUDED.version,"
                        + " active = TRUE, imported_count = EXCLUDED.imported_count, updated_at = NOW()",
                sourceId, fileName, fileHash, version, importedCount);
    }

    /**
     * 查询知识库统计信息。
     *
     * <p>统计口径直接来自向量库表，用于判断评分标准是否已经导入、来源是否完整。</p>
     */
    public Map<String, Object> getKnowledgeStats() {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + VECTOR_TABLE, Long.class);
        Long detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + VECTOR_TABLE + " WHERE metadata ->> 'source' = ?",
                Long.class, "评分细则");
        Long penaltyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + VECTOR_TABLE + " WHERE metadata ->> 'source' = ?",
                Long.class, "扣分项参考");

        List<String> sources = jdbcTemplate.queryForList("""
                        SELECT DISTINCT metadata ->> 'source'
                        FROM vector_store
                        WHERE metadata ->> 'source' IS NOT NULL
                        ORDER BY metadata ->> 'source'
                        """,
                String.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0L : total);
        result.put("detailCount", detailCount == null ? 0L : detailCount);
        result.put("penaltyCount", penaltyCount == null ? 0L : penaltyCount);
        result.put("sources", sources);
        return result;
    }

    /**
     * 分页查询知识源（版本历史）。
     *
     * <p>documentCount 动态统计当前库中该知识源的文档数，用于判断版本是否仍生效。</p>
     */
    public PageResult<KnowledgeSourceVO> getKnowledgeSources(int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safePageSize;

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_source", Long.class);

        List<KnowledgeSourceVO> items = jdbcTemplate.query("""
                        SELECT ks.id::text AS id,
                               ks.file_name,
                               ks.version,
                               ks.active,
                               ks.imported_count,
                               ks.created_at,
                               ks.updated_at,
                               (SELECT COUNT(*) FROM vector_store vs
                                WHERE vs.metadata ->> 'source_id' = ks.id::text) AS document_count
                        FROM knowledge_source ks
                        ORDER BY ks.updated_at DESC
                        LIMIT ? OFFSET ?
                        """,
                (rs, rowNum) -> new KnowledgeSourceVO(
                        rs.getString("id"),
                        rs.getString("file_name"),
                        rs.getInt("version"),
                        rs.getBoolean("active"),
                        rs.getLong("imported_count"),
                        rs.getLong("document_count"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)),
                safePageSize, offset);

        return PageResult.of(items, total == null ? 0L : total, safePage, safePageSize);
    }

    /**
     * 分页查询知识库片段。
     *
     * <p>这里使用 JdbcTemplate 直接查询 pgvector 表，是因为 Spring AI 的 VectorStore
     * 更适合相似度检索，不适合做后台管理分页。</p>
     */
    public PageResult<KnowledgeDocumentVO> getKnowledgeDocuments(int page, int pageSize,
                                                                 String keyword, String source) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safePageSize;

        List<Object> args = new ArrayList<>();
        String whereSql = buildKnowledgeWhereSql(keyword, source, args);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + VECTOR_TABLE + " " + whereSql,
                Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safePageSize);
        pageArgs.add(offset);
        List<KnowledgeDocumentVO> items = jdbcTemplate.query("""
                        SELECT
                            id::text AS id,
                            content,
                            COALESCE(metadata ->> 'source', '') AS source,
                            COALESCE(metadata ->> 'dimension', '') AS dimension,
                            COALESCE(metadata ->> 'level', '') AS level,
                            COALESCE(metadata ->> 'severity', '') AS severity,
                            NULLIF(metadata ->> 'row', '')::int AS row_number
                        FROM vector_store
                        %s
                        ORDER BY metadata ->> 'source' ASC,
                                 NULLIF(metadata ->> 'row', '')::int ASC NULLS LAST,
                                 id ASC
                        LIMIT ? OFFSET ?
                        """.formatted(whereSql),
                knowledgeDocumentRowMapper,
                pageArgs.toArray());

        return PageResult.of(items, total == null ? 0L : total, safePage, safePageSize);
    }

    /**
     * 删除知识库片段。
     *
     * <p>先确认片段存在，再通过 VectorStore 删除，保证向量索引和管理列表保持一致。</p>
     */
    public Map<String, Object> deleteKnowledgeDocument(String id) {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("知识片段 ID 不能为空");
        }

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + VECTOR_TABLE + " WHERE id = CAST(? AS uuid)",
                Long.class, id);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("知识片段不存在");
        }

        vectorStore.delete(List.of(id));
        auditLogService.log(DEFAULT_OPERATOR, "KNOWLEDGE_DOCUMENT_DELETED", "scoring_rubric",
                id, Map.of("id", id));

        return MapUtil.<String, Object>builder("id", id)
                .put("deleted", true)
                .build();
    }

    /**
     * 解析评分细则 sheet
     *
     * <p>每行包含: 维度、等级、分值范围、标准描述、好示例、差示例。
     * 将每行组装为一个 Document，维度+等级作为 metadata。</p>
     */
    private List<Document> parseDetailSheet(Sheet sheet) {
        List<Document> documents = new ArrayList<>();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }

            String dimension = getCellString(row.getCell(0));
            String level = getCellString(row.getCell(1));
            String scoreRange = getCellString(row.getCell(2));
            String description = getCellString(row.getCell(3));
            String goodExample = getCellString(row.getCell(4));
            String badExample = getCellString(row.getCell(5));

            if (dimension.isEmpty() && description.isEmpty()) {
                continue;
            }

            StringBuilder content = new StringBuilder();
            content.append("[").append(dimension).append("-").append(level).append("] ");
            content.append("维度: ").append(dimension).append("\n");
            content.append("等级: ").append(level).append("\n");
            content.append("分值范围: ").append(scoreRange).append("\n");
            content.append("评分标准: ").append(description).append("\n");
            if (!goodExample.isEmpty()) {
                content.append("优秀示例: ").append(goodExample).append("\n");
            }
            if (!badExample.isEmpty()) {
                content.append("反面示例: ").append(badExample).append("\n");
            }

            Map<String, Object> metadata = MapUtil.newHashMap(5);
            metadata.put("source", "评分细则");
            metadata.put("dimension", dimension);
            metadata.put("level", level);
            metadata.put("row", i);

            documents.add(new Document(content.toString(), metadata));
        }

        return documents;
    }

    /**
     * 解析扣分项参考 sheet
     *
     * <p>每行会转换成一个 Document，metadata 中记录维度、严重程度和原 Excel 行号，
     * 后续检索和后台展示都依赖这些 metadata。</p>
     */
    private List<Document> parsePenaltySheet(Sheet sheet) {
        List<Document> documents = new ArrayList<>();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }

            String dimension = getCellString(row.getCell(0));
            String penaltyItem = getCellString(row.getCell(1));
            String penaltyValue = getCellString(row.getCell(2));
            String severity = getCellString(row.getCell(3));
            String note = getCellString(row.getCell(4));

            if (dimension.isEmpty() && penaltyItem.isEmpty()) {
                continue;
            }

            StringBuilder content = new StringBuilder();
            content.append("[扣分-").append(dimension).append("-").append(severity).append("] ");
            content.append("维度: ").append(dimension).append("\n");
            content.append("扣分项: ").append(penaltyItem).append("\n");
            content.append("扣分值: ").append(penaltyValue).append("\n");
            content.append("严重程度: ").append(severity).append("\n");
            content.append("说明: ").append(note).append("\n");

            Map<String, Object> metadata = MapUtil.newHashMap(5);
            metadata.put("source", "扣分项参考");
            metadata.put("dimension", dimension);
            metadata.put("severity", severity);
            metadata.put("row", i);

            documents.add(new Document(content.toString(), metadata));
        }

        return documents;
    }

    /**
     * 构建知识库分页查询的 WHERE 条件。
     *
     * <p>SQL 条件和参数分开组装，避免直接拼接用户输入。</p>
     */
    private String buildKnowledgeWhereSql(String keyword, String source, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            conditions.add("content ILIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        if (StringUtils.hasText(source)) {
            conditions.add("metadata ->> 'source' = ?");
            args.add(source.trim());
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
    }

    /**
     * 安全读取单元格字符串值
     *
     * <p>Excel 中同一列可能被用户填成文本、数字或布尔值，这里统一转成字符串，
     * 避免导入时因为单元格类型不同而失败。</p>
     */
    private String getCellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((int) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /**
     * 知识源查询结果行。
     */
    private record KnowledgeSourceRow(UUID id, String fileName, String fileHash,
                                      int version, boolean active) {
    }
}