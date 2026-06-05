package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.SystemUserInfo;
import com.si.backend.mapper.SystemUserInfoMapper;
import com.si.backend.vo.SystemUserImportResultVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Service for system-wide user profile CRUD and Excel imports.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemUserInfoService {

    private static final int HEADER_SCAN_LIMIT = 10;
    private static final int FIRST_DATA_ROW_OFFSET = 1;
    private static final int DEFAULT_DEPARTMENT_COL = 1;
    private static final int DEFAULT_NAME_COL = 2;
    private static final int DEFAULT_POSITION_COL = 3;
    private static final int DEFAULT_EMAIL_COL = 4;
    private static final int DEFAULT_MICROSOFT_ID_COL = 5;
    private static final int DEFAULT_ROBIN_UID_COL = 6;
    private static final int DEFAULT_TEAMS_VERIFIED_COL = 7;
    private static final int DEFAULT_EMPLOYMENT_STATUS_COL = 8;

    private final SystemUserInfoMapper userInfoMapper;

    @PostConstruct
    public void initTable() {
        log.info("[SystemUserInfoService] initTable start");
        userInfoMapper.createTableIfNotExists();
        try {
            userInfoMapper.addNationalityColumnIfNotExists();
        } catch (org.springframework.dao.DataAccessException e) {
            if (e.getMessage() == null || !e.getMessage().contains("Duplicate column")) {
                log.warn("[SystemUserInfoService] addNationalityColumn failed: {}", e.getMessage());
            }
        }
        log.info("[SystemUserInfoService] initTable end");
    }

    public List<SystemUserInfo> list(String keyword) {
        log.info("[SystemUserInfoService] list start, keywordLen={}", keyword != null ? keyword.length() : 0);
        List<SystemUserInfo> result = userInfoMapper.findAll(trimToNull(keyword));
        log.info("[SystemUserInfoService] list end, count={}", result.size());
        return result;
    }

    @Transactional
    public SystemUserInfo create(SystemUserInfo userInfo) {
        log.info("[SystemUserInfoService] create start, email={}", userInfo != null ? userInfo.getEmail() : null);
        normalizeAndValidate(userInfo);
        if (userInfoMapper.findByEmail(userInfo.getEmail()) != null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "邮箱已存在");
        }
        userInfoMapper.insert(userInfo);
        log.info("[SystemUserInfoService] create end, id={}", userInfo.getId());
        return userInfo;
    }

    @Transactional
    public SystemUserInfo update(Long id, SystemUserInfo userInfo) {
        log.info("[SystemUserInfoService] update start, id={}", id);
        SystemUserInfo existing = userInfoMapper.findById(id);
        if (existing == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "用户信息不存在");
        }
        userInfo.setId(id);
        normalizeAndValidate(userInfo);
        SystemUserInfo sameEmail = userInfoMapper.findByEmail(userInfo.getEmail());
        if (sameEmail != null && !sameEmail.getId().equals(id)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "邮箱已被其他用户使用");
        }
        userInfoMapper.update(userInfo);
        SystemUserInfo updated = userInfoMapper.findById(id);
        log.info("[SystemUserInfoService] update end, id={}", id);
        return updated;
    }

    @Transactional
    public void delete(Long id) {
        log.info("[SystemUserInfoService] delete start, id={}", id);
        int rows = userInfoMapper.deleteById(id);
        if (rows == 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "用户信息不存在");
        }
        log.info("[SystemUserInfoService] delete end, id={}", id);
    }

    @Transactional
    public SystemUserImportResultVo importExcel(MultipartFile file) {
        String fileName = file != null ? file.getOriginalFilename() : null;
        log.info("[SystemUserInfoService] importExcel start, fileName={}, size={}",
                fileName, file != null ? file.getSize() : 0);
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择要上传的 Excel 文件");
        }
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            // Read EVERY sheet; the sheet name is the nationality (表格按国籍分 sheet).
            List<ImportSheet> importSheets = findAllImportSheets(workbook);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            int created = 0;
            int updated = 0;
            int skipped = 0;
            List<String> sheetNames = new ArrayList<>();
            for (ImportSheet importSheet : importSheets) {
                String sheetName = importSheet.sheet().getSheetName();
                sheetNames.add(sheetName);
                for (int rowIndex = importSheet.headerRowIndex() + FIRST_DATA_ROW_OFFSET;
                     rowIndex <= importSheet.sheet().getLastRowNum();
                     rowIndex++) {
                    Row row = importSheet.sheet().getRow(rowIndex);
                    SystemUserInfo rowUser = toUserInfo(row, formatter, importSheet.columns(), sheetName, rowIndex + 1);
                    if (rowUser == null) {
                        skipped++;
                        continue;
                    }
                    rowUser.setNationality(sheetName);   // 国籍 = sheet 名
                    normalizeAndValidate(rowUser);
                    if (saveImportedUser(rowUser)) {
                        created++;
                    } else {
                        updated++;
                    }
                }
            }
            SystemUserImportResultVo result = SystemUserImportResultVo.builder()
                    .sheetName(String.join("、", sheetNames))
                    .createdCount(created)
                    .updatedCount(updated)
                    .skippedCount(skipped)
                    .totalCount(created + updated)
                    .build();
            log.info("[SystemUserInfoService] importExcel end, sheet={}, created={}, updated={}, skipped={}",
                    result.getSheetName(), created, updated, skipped);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[SystemUserInfoService] importExcel read failed, fileName={}", fileName, e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "Excel 文件读取失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("[SystemUserInfoService] importExcel parse failed, fileName={}", fileName, e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "Excel 文件解析失败：" + e.getMessage());
        }
    }

    private boolean saveImportedUser(SystemUserInfo rowUser) {
        SystemUserInfo existing = findImportMatch(rowUser);
        if (existing == null) {
            userInfoMapper.insert(rowUser);
            return true;
        }
        SystemUserInfo emailOwner = userInfoMapper.findByEmail(rowUser.getEmail());
        if (emailOwner != null && !emailOwner.getId().equals(existing.getId())) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "邮箱 " + rowUser.getEmail() + " 已属于另一条用户信息，请先检查重复数据");
        }
        rowUser.setId(existing.getId());
        userInfoMapper.updateFromImport(rowUser);
        return false;
    }

    private SystemUserInfo findImportMatch(SystemUserInfo rowUser) {
        String microsoftId = trimToNull(rowUser.getMicrosoftId());
        if (microsoftId != null) {
            SystemUserInfo matched = userInfoMapper.findByMicrosoftId(microsoftId);
            if (matched != null) {
                return matched;
            }
        }
        String robinUid = trimToNull(rowUser.getRobinUid());
        if (isUsefulRobinUid(robinUid)) {
            SystemUserInfo matched = userInfoMapper.findByRobinUid(robinUid);
            if (matched != null) {
                return matched;
            }
        }
        SystemUserInfo emailMatched = userInfoMapper.findByEmail(rowUser.getEmail());
        if (emailMatched != null) {
            return emailMatched;
        }
        return userInfoMapper.findByPersonNameAndDepartment(rowUser.getPersonName(), rowUser.getDepartment());
    }

    private boolean isUsefulRobinUid(String robinUid) {
        return robinUid != null && !"0".equals(robinUid);
    }

    /** Every sheet with name+email headers; each sheet name is a nationality (中国人 / 华人 / 印尼人). */
    private List<ImportSheet> findAllImportSheets(Workbook workbook) {
        List<ImportSheet> sheets = new ArrayList<>();
        for (Sheet sheet : workbook) {
            for (int rowIndex = 0; rowIndex <= Math.min(sheet.getLastRowNum(), HEADER_SCAN_LIMIT); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                Map<String, Integer> columns = resolveColumns(row);
                if (columns.containsKey("email") && columns.containsKey("personName")) {
                    Map<String, Integer> importColumns = defaultColumns();
                    importColumns.putAll(columns);
                    log.info("[SystemUserInfoService] import sheet selected, sheet={}, headerRow={}",
                            sheet.getSheetName(), rowIndex + 1);
                    sheets.add(new ImportSheet(sheet, rowIndex, importColumns));
                    break;   // header found for this sheet; move to next sheet
                }
            }
        }
        if (sheets.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "未找到包含姓名和邮箱列的工作表");
        }
        return sheets;
    }

    private Map<String, Integer> resolveColumns(Row row) {
        Map<String, Integer> columns = new HashMap<>();
        if (row == null) {
            return columns;
        }
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        for (Cell cell : row) {
            String header = normalizeHeader(formatter.formatCellValue(cell));
            if (header.contains("department") || header.contains("部门")) {
                columns.put("department", cell.getColumnIndex());
            } else if (header.contains("name") || header.contains("nama") || header.contains("姓名")) {
                columns.put("personName", cell.getColumnIndex());
            } else if (header.contains("position") || header.contains("jabatan") || header.contains("职位")) {
                columns.put("positionTitle", cell.getColumnIndex());
            } else if (header.contains("email") || header.contains("mail") || header.contains("邮箱")) {
                columns.put("email", cell.getColumnIndex());
            } else if (header.contains("microsoft")) {
                columns.put("microsoftId", cell.getColumnIndex());
            } else if (header.contains("robin")) {
                columns.put("robinUid", cell.getColumnIndex());
            } else if (header.contains("teams")) {
                columns.put("teamsVerified", cell.getColumnIndex());
            } else if (header.contains("employment") || header.contains("status") || header.contains("在职")) {
                columns.put("employmentStatus", cell.getColumnIndex());
            }
        }
        return columns;
    }

    private Map<String, Integer> defaultColumns() {
        Map<String, Integer> columns = new HashMap<>();
        columns.put("department", DEFAULT_DEPARTMENT_COL);
        columns.put("personName", DEFAULT_NAME_COL);
        columns.put("positionTitle", DEFAULT_POSITION_COL);
        columns.put("email", DEFAULT_EMAIL_COL);
        columns.put("microsoftId", DEFAULT_MICROSOFT_ID_COL);
        columns.put("robinUid", DEFAULT_ROBIN_UID_COL);
        columns.put("teamsVerified", DEFAULT_TEAMS_VERIFIED_COL);
        columns.put("employmentStatus", DEFAULT_EMPLOYMENT_STATUS_COL);
        return columns;
    }

    private SystemUserInfo toUserInfo(
            Row row,
            DataFormatter formatter,
            Map<String, Integer> columns,
            String sheetName,
            int sourceRow
    ) {
        if (row == null) {
            return null;
        }
        String personName = cellText(row, columns.get("personName"), formatter);
        String email = normalizeEmail(cellText(row, columns.get("email"), formatter));
        if (personName == null || email == null) {
            return null;
        }
        SystemUserInfo userInfo = new SystemUserInfo();
        userInfo.setDepartment(cellText(row, columns.get("department"), formatter));
        userInfo.setPersonName(personName);
        userInfo.setPositionTitle(cellText(row, columns.get("positionTitle"), formatter));
        userInfo.setEmail(email);
        userInfo.setMicrosoftId(cellText(row, columns.get("microsoftId"), formatter));
        userInfo.setRobinUid(cellText(row, columns.get("robinUid"), formatter));
        userInfo.setTeamsVerified(cellText(row, columns.get("teamsVerified"), formatter));
        userInfo.setEmploymentStatus(cellText(row, columns.get("employmentStatus"), formatter));
        userInfo.setSourceSheet(sheetName);
        userInfo.setSourceRow(sourceRow);
        return userInfo;
    }

    private String cellText(Row row, Integer columnIndex, DataFormatter formatter) {
        if (row == null || columnIndex == null || columnIndex < 0) {
            return null;
        }
        Cell cell = row.getCell(columnIndex);
        String value = cell == null ? null : formatter.formatCellValue(cell);
        return trimToNull(value);
    }

    private void normalizeAndValidate(SystemUserInfo userInfo) {
        if (userInfo == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "用户信息不能为空");
        }
        userInfo.setDepartment(trimToNull(userInfo.getDepartment()));
        userInfo.setPersonName(trimToNull(userInfo.getPersonName()));
        userInfo.setPositionTitle(trimToNull(userInfo.getPositionTitle()));
        userInfo.setEmail(normalizeEmail(userInfo.getEmail()));
        userInfo.setMicrosoftId(trimToNull(userInfo.getMicrosoftId()));
        userInfo.setRobinUid(trimToNull(userInfo.getRobinUid()));
        userInfo.setTeamsVerified(trimToNull(userInfo.getTeamsVerified()));
        userInfo.setEmploymentStatus(trimToNull(userInfo.getEmploymentStatus()));
        if (userInfo.getPersonName() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "姓名不能为空");
        }
        if (userInfo.getEmail() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "邮箱不能为空");
        }
        if (!userInfo.getEmail().contains("@")) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
    }

    private String normalizeHeader(String text) {
        String value = trimToNull(text);
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private String normalizeEmail(String email) {
        String value = trimToNull(email);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ImportSheet(Sheet sheet, int headerRowIndex, Map<String, Integer> columns) {
    }
}
