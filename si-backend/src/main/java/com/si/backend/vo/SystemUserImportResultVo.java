package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Summary returned after importing system users from an Excel workbook.
 */
@Data
@Builder
public class SystemUserImportResultVo {

    private String sheetName;
    private Integer createdCount;
    private Integer updatedCount;
    private Integer skippedCount;
    private Integer totalCount;
}
