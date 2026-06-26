package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * ASR 错词记录(错词库)实体，对应 asr_correction 表。
 *
 * <p>持续累积、跨会议复用：记录 ASR 把某个词听错的写法({@code variant})与正确写法({@code canonical})，
 * 用于实时纠错翻译时作为"疑似听错"参考喂给 LLM，以及作为正确词形喂给 ASR 热词。
 * 主要由"会后文档×ASR 对比"自动挖掘写入，按命中频次/置信度自动从 OBSERVING 升为 ACTIVE。
 */
@Data
public class AsrCorrection {

    private Long id;
    private Long userId;
    /** ASR 听错的写法(匹配键，存小写) */
    private String variant;
    /** 正确写法(术语/人名/数字单位等) */
    private String canonical;
    /** 适用源语言：id / en / zh；空=不限 */
    private String srcLang;
    /** 适用范围：GLOBAL / COMPANY / TOPIC（预留，默认 GLOBAL） */
    private String scope;
    /** 置信度 0~1（来自挖掘 LLM 或人工） */
    private Double confidence;
    /** 命中次数（跨会议累加，用于自动升级） */
    private Integer hitCount;
    /** 状态：OBSERVING(观察、暂不强用) / ACTIVE(生效，参与纠错) */
    private String status;
    /** 来源：DOC_MINING(文档挖掘) / MANUAL(手动) */
    private String source;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
