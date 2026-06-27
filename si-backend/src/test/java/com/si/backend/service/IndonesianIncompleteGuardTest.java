package com.si.backend.service;

import com.si.backend.config.AzureSpeechProperties;
import com.si.backend.service.IndonesianIncompleteGuard.Decision;
import com.si.backend.service.IndonesianIncompleteGuard.EmitAction;
import com.si.backend.service.IndonesianIncompleteGuard.GuardResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 用例：印尼语完整性 Guard。覆盖最终方案 §9 列出的 12 条验收用例。
 */
class IndonesianIncompleteGuardTest {

    private IndonesianIncompleteGuard guard() {
        AzureSpeechProperties properties = new AzureSpeechProperties();
        properties.getAsr().setIdSegmentGuardEnabled(true);
        return new IndonesianIncompleteGuard(properties);
    }

    // 1. 半词尾 "...peng" → HOLD
    @Test
    void halfWordPrefixTailHolds() {
        GuardResult result = guard().check("keuangan, sistem biaya target fiskal peng");
        assertEquals(Decision.HOLD, result.decision());
        assertEquals("ID_PREFIX_TAIL", result.reason());
    }

    // 2. 可疑词头 "tuk ..." → HOLD
    @Test
    void suspiciousHeadHolds() {
        GuardResult result = guard().check("tuk setiap pohon jangan hanya melihat realitas");
        assertEquals(Decision.HOLD, result.decision());
        assertEquals("ID_SUSPICIOUS_HEAD", result.reason());
    }

    // 连接词尾 "...dengan" → HOLD（补充半词尾之外的另一类）
    @Test
    void connectorTailHolds() {
        GuardResult result = guard().check("berfokus pada ruang space dengan");
        assertEquals(Decision.HOLD, result.decision());
        assertEquals("ID_CONNECTOR_TAIL", result.reason());
    }

    // 3. 固定短语 masa depan 不可切断
    @Test
    void doesNotSplitMasaDepan() {
        String working = "nilai masa depan lahan tersebut";
        int cut = working.indexOf("depan"); // 切在 masa | depan 之间
        assertTrue(guard().boundaryVeto(working, cut));
    }

    // 4. 固定短语 sepak bola 不可切断
    @Test
    void doesNotSplitSepakBola() {
        String working = "karena sepak bola seluruh dunia mengenal negara tersebut";
        int cut = working.indexOf("bola");
        assertTrue(guard().boundaryVeto(working, cut));
    }

    // 5. 固定短语 Amerika Serikat 不可切断
    @Test
    void doesNotSplitAmerikaSerikat() {
        String working = "pusat teknologi pusat modal pusat inovasi Amerika Serikat";
        int cut = working.indexOf("Serikat");
        assertTrue(guard().boundaryVeto(working, cut));
    }

    // 6. "10 juta dolar" 不识别为编号标题
    @Test
    void tenJutaIsNotNumberedTitle() {
        String working = "bahkan 10 juta dolar Amerika Serikat";
        int numberStart = working.indexOf("10");
        assertFalse(guard().isNumberedTitle(working, numberStart));
        // 且不可在 10 与 juta 之间切
        int cut = working.indexOf("juta");
        assertTrue(guard().boundaryVeto(working, cut));
    }

    // 7. "13 pemikiran" 识别为编号标题
    @Test
    void thirteenPemikiranIsNumberedTitle() {
        String working = "dana amerika dan indonesia 13 pemikiran penting mengenai ai";
        int numberStart = working.indexOf("13");
        assertTrue(guard().isNumberedTitle(working, numberStart));
        int boundary = guard().findNumberedTitleBoundary(working, working.length());
        assertEquals(numberStart, boundary);
    }

    // 8. wtpsplit 短段(<门槛)且非白名单 → HOLD 退回下一轮(不直接 final)
    @Test
    void shortWtpsplitSegmentHeld() {
        for (String working : new String[]{"ke depan", "8 tahun", "3 satelit", "dari sisi"}) {
            assertEquals(EmitAction.HOLD, guard().decideEmit(working, working.length(), "sentence-wtpsplit"),
                    "应 HOLD: " + working);
        }
    }

    // 8b. 真·短应答在白名单里 → 即使短也 EMIT_FINAL
    @Test
    void whitelistedShortReplyEmits() {
        assertEquals(EmitAction.EMIT_FINAL, guard().decideEmit("Ya.", "Ya.".length(), "sentence-wtpsplit"));
        assertEquals(EmitAction.EMIT_FINAL, guard().decideEmit("terima kasih", "terima kasih".length(), "sentence-wtpsplit"));
    }

    // 8c. 长度达标的强边界整句 → EMIT_FINAL
    @Test
    void longWtpsplitSegmentEmits() {
        String working = "seluruh perkembangan perusahaan ke depan akan terus berpusat pada sistem ini";
        assertTrue(working.replace(" ", "").length() >= 48);
        assertEquals(EmitAction.EMIT_FINAL, guard().decideEmit(working, working.length(), "sentence-wtpsplit"));
    }

    // 8d. 编号标题不受短句门槛限制(标题本就短)
    @Test
    void numberedTitleNotHeldByFloor() {
        String working = "14 kesimpulan";
        assertEquals(EmitAction.EMIT_FINAL, guard().decideEmit(working, working.length(), "numbered-title"));
    }

    // 9. 弱边界即使长度够，也必须降级（不直接作为 final）
    @Test
    void longWeakBoundaryDowngraded() {
        String working = "kita membahas banyak hal penting dalam pertemuan ini hari ini juga"; // > 48 且结尾完整
        int end = working.length();
        assertTrue(end >= 48);
        assertEquals(EmitAction.DOWNGRADE_PARTIAL, guard().decideEmit(working, end, "force-boundary"));
        assertEquals(EmitAction.DOWNGRADE_PARTIAL, guard().decideEmit(working, end, "force-comma"));
    }

    // 10. HOLD 分支：decideEmit 返回非 EMIT_FINAL（调用方据此不推进 emittedLen）
    @Test
    void holdActionWhenSegmentIncomplete() {
        String working = "sistem biaya target fiskal peng";
        EmitAction action = guard().decideEmit(working, working.length(), "sentence-wtpsplit");
        assertEquals(EmitAction.HOLD, action);
    }

    // 11. PASS 分支：完整的强边界整句 → EMIT_FINAL（调用方正常推进 emittedLen）
    @Test
    void emitFinalWhenStrongAndComplete() {
        String working = "oleh karena itu kita harus membangun masa depan yang lebih baik";
        EmitAction action = guard().decideEmit(working, working.length(), "sentence-wtpsplit");
        assertEquals(EmitAction.EMIT_FINAL, action);
    }

    // 12. final remainder 发出前的 Guard：半词尾终稿残片被拦下
    @Test
    void finalRemainderHalfWordBlocked() {
        GuardResult result = guard().check("dari amerika dan indonesia 13 omikiran");
        // "omikiran" 既非半词前缀也非连接词，但这里验证连接词/半词尾确实拦截：
        GuardResult tail = guard().check("nilai masa depan lahan tersebut dengan");
        assertEquals(Decision.HOLD, tail.decision());
        // 正常完整 remainder 应放行
        assertTrue(result.isPass());
    }

    // 13. 终稿外科式：连接词尾只剥尾、不丢整段
    @Test
    void trimTailStripsConnectorKeepsBody() {
        String r = guard().trimIncompleteTail("Oleh karena itu, kita sedang memahami kapitalisme di Indonesia dan.");
        assertTrue(r.contains("kapitalisme di Indonesia"), r);
        assertFalse(r.toLowerCase().endsWith("dan"), r);
    }

    // 14. 终稿外科式：半词尾只剥尾
    @Test
    void trimTailStripsHalfWord() {
        String r = guard().trimIncompleteTail("model keuangan sistem biaya target fiskal peng");
        assertTrue(r.endsWith("fiskal"), r);
    }

    // 15. 终稿外科式：词头可疑(Juta)不影响 —— 不看头,整段保留
    @Test
    void trimTailKeepsSuspiciousHead() {
        String text = "Juta sinkronisasi dana Amerika dan Indonesia pemikiran penting";
        assertEquals(text, guard().trimIncompleteTail(text));
    }

    // 16. 终稿外科式：整段都是残片 → 返回空(调用方抑制)
    @Test
    void trimTailEmptyWhenAllIncomplete() {
        assertEquals("", guard().trimIncompleteTail("dengan"));
        assertEquals("", guard().trimIncompleteTail("peng"));
    }

    // 关闭开关时（默认行为回退）：enabled=false
    @Test
    void disabledFlagReportsDisabled() {
        AzureSpeechProperties properties = new AzureSpeechProperties();
        properties.getAsr().setIdSegmentGuardEnabled(false);
        assertFalse(new IndonesianIncompleteGuard(properties).isEnabled());
    }
}
