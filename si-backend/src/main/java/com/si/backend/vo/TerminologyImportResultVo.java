package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Summary returned after importing terminology from an Excel workbook.
 */
@Data
@Builder
public class TerminologyImportResultVo {

    private String sheetName;
    private Integer createdCount;
    private Integer skippedCount;
    private Integer totalCount;
}
