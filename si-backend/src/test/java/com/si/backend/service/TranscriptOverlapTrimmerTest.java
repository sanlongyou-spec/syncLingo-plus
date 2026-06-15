package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranscriptOverlapTrimmerTest {

    @Test
    void removesExpandedRepeatAndKeepsOnlyNewSuffix() {
        var result = TranscriptOverlapTrimmer.trim(
                "感谢易总的报告。易总的报告指出，在当前条件下，生产厂具有盈利的潜力，",
                "感谢易总的报告。易总的报告指出，在当前条件下，生产厂具有盈利的潜力，关键点在于提升开机率。"
        );

        assertEquals("关键点在于提升开机率。", result.text());
        assertEquals(30, result.overlapChars());
    }

    @Test
    void removesCompleteRepeatEvenWhenPunctuationDiffers() {
        var result = TranscriptOverlapTrimmer.trim(
                "呃，对标柴油价格是14.5的话。",
                "呃 对标柴油价格是 14 . 5 的话"
        );

        assertEquals("", result.text());
        assertEquals(13, result.overlapChars());
    }

    @Test
    void removesRealShortPhraseRepeat() {
        var result = TranscriptOverlapTrimmer.trim("汇报完毕，谢谢。", "谢谢。下面进入下一个议题。");

        assertEquals("下面进入下一个议题。", result.text());
        assertEquals(2, result.overlapChars());
    }

    @Test
    void repeatedlyRemovesOverlapStillPresentAfterFirstTrim() {
        var result = TranscriptOverlapTrimmer.trim(
                "按照这个没有折旧来算的话，就是同时把热值也折算进去，",
                "来算的话，就是同时把热值也折算进去，折算进去就是按照一比一点一折算。"
        );

        assertEquals("就是按照一比一点一折算。", result.text());
        assertEquals(20, result.overlapChars());
    }

    @Test
    void ignoresSingleCharacterIncidentalOverlap() {
        var result = TranscriptOverlapTrimmer.trim("这是上一段的。", "的确需要重新说明。");

        assertEquals("的确需要重新说明。", result.text());
        assertEquals(0, result.overlapChars());
    }

    @Test
    void keepsUnrelatedSegment() {
        var result = TranscriptOverlapTrimmer.trim("第一阶段完成。", "第二阶段开始。");

        assertEquals("第二阶段开始。", result.text());
        assertEquals(0, result.overlapChars());
    }
}
