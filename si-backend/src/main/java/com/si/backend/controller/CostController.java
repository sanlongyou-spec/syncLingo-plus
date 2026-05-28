package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.config.CostRatesProperties;
import com.si.backend.mapper.InterpretationSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/cost")
@RequiredArgsConstructor
public class CostController {

    private final CostRatesProperties costRates;
    private final InterpretationSessionMapper sessionMapper;

    /**
     * S3: Return current billing rates so the frontend can compute cost without
     * hardcoded constants. Rates are per-unit (e.g. per millisecond of ASR audio).
     */
    @GetMapping("/rates")
    public Result<Map<String, Object>> getRates() {
        CostRatesProperties.Rates r = costRates.getRates();
        CostRatesProperties.Budget b = costRates.getBudget();
        return Result.ok(Map.of(
                "asrPerMs",          r.getAsrPerHourUsd() / 3_600_000.0,
                "transPerChar",      r.getTransPerMillionCharsUsd() / 1_000_000.0,
                "ttsPerChar",        r.getTtsPerMillionCharsUsd() / 1_000_000.0,
                "llmInPerToken",     r.getLlmInPerMillionTokensUsd() / 1_000_000.0,
                "llmOutPerToken",    r.getLlmOutPerMillionTokensUsd() / 1_000_000.0,
                "monthlyBudgetUsd",  b.getMonthlyUsd(),
                "sessionBudgetUsd",  b.getSessionUsd()
        ));
    }

    /**
     * S7: Monthly cost summary for a user. Each row contains month, sessionCount,
     * usage totals, and estimated cost computed server-side.
     */
    @GetMapping("/monthly-summary")
    public Result<List<Map<String, Object>>> getMonthlySummary(@RequestParam Long userId) {
        log.info("[CostController] getMonthlySummary userId={}", userId);
        List<Map<String, Object>> rows = sessionMapper.monthlySummaryByUser(userId);
        CostRatesProperties.Rates r = costRates.getRates();
        rows.forEach(row -> {
            double cost = calcCostFromRow(row, r);
            row.put("estimatedCostUsd", cost);
        });
        return Result.ok(rows);
    }

    public double calcSessionCostUsd(com.si.backend.entity.InterpretationSession session) {
        CostRatesProperties.Rates r = costRates.getRates();
        double asr   = (session.getAsrAudioMs()     != null ? session.getAsrAudioMs()     : 0L) * r.getAsrPerHourUsd() / 3_600_000.0;
        double trans = (session.getTranslateChars()  != null ? session.getTranslateChars()  : 0L) * r.getTransPerMillionCharsUsd() / 1_000_000.0;
        double tts   = (session.getTtsChars()        != null ? session.getTtsChars()        : 0L) * r.getTtsPerMillionCharsUsd() / 1_000_000.0;
        double llm   = (session.getLlmInputTokens()  != null ? session.getLlmInputTokens()  : 0L) * r.getLlmInPerMillionTokensUsd() / 1_000_000.0
                     + (session.getLlmOutputTokens() != null ? session.getLlmOutputTokens() : 0L) * r.getLlmOutPerMillionTokensUsd() / 1_000_000.0;
        return asr + trans + tts + llm;
    }

    private double calcCostFromRow(Map<String, Object> row, CostRatesProperties.Rates r) {
        long asrMs     = toLong(row.get("totalAsrMs"));
        long transChars = toLong(row.get("totalTransChars"));
        long ttsChars  = toLong(row.get("totalTtsChars"));
        long llmIn     = toLong(row.get("totalLlmIn"));
        long llmOut    = toLong(row.get("totalLlmOut"));
        double asr   = asrMs     * r.getAsrPerHourUsd() / 3_600_000.0;
        double trans = transChars * r.getTransPerMillionCharsUsd() / 1_000_000.0;
        double tts   = ttsChars  * r.getTtsPerMillionCharsUsd() / 1_000_000.0;
        double llm   = llmIn     * r.getLlmInPerMillionTokensUsd() / 1_000_000.0
                     + llmOut    * r.getLlmOutPerMillionTokensUsd() / 1_000_000.0;
        return asr + trans + tts + llm;
    }

    private long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (NumberFormatException e) { return 0L; }
    }
}
