package com.si.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.dto.SaveUserSummaryRequirementsRequest;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
import com.si.backend.vo.UserSummaryRequirementsVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    private final UserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void initColumns() {
        addColumnIfMissing("summary_recipients", "TEXT DEFAULT NULL");
        addColumnIfMissing("meeting_summary_requirements", "TEXT DEFAULT NULL");
        addColumnIfMissing("speaker_summary_requirements", "TEXT DEFAULT NULL");
    }

    private void addColumnIfMissing(String columnName, String definition) {
        try {
            jdbcTemplate.execute("ALTER TABLE si_user ADD COLUMN " + columnName + " " + definition);
            log.info("[UserPreferenceService] column added, column={}", columnName);
        } catch (Exception e) {
            // Spring wraps MySQL duplicate-column errors, so inspect the full cause chain.
            if (causeChainContains(e, "Duplicate column")) {
                log.debug("[UserPreferenceService] column already exists, column={}", columnName);
            } else {
                log.warn("[UserPreferenceService] failed to add column, column={}, error={}",
                        columnName, e.getMessage());
            }
        }
    }

    private static boolean causeChainContains(Throwable t, String keyword) {
        while (t != null) {
            if (t.getMessage() != null && t.getMessage().contains(keyword)) return true;
            t = t.getCause();
        }
        return false;
    }

    public List<String> getSummaryRecipients(Long userId) {
        log.info("[UserPreferenceService] getSummaryRecipients start, userId={}", userId);
        String json = userMapper.getSummaryRecipients(userId);
        if (json == null || json.isBlank()) {
            log.info("[UserPreferenceService] getSummaryRecipients end, userId={}, count=0", userId);
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            List<String> result = parsed != null ? parsed : List.of();
            log.info("[UserPreferenceService] getSummaryRecipients end, userId={}, count={}",
                    userId, result.size());
            return result;
        } catch (Exception e) {
            log.warn("[UserPreferenceService] failed to parse summary_recipients for userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    public void saveSummaryRecipients(Long userId, List<String> recipients) {
        List<String> safeRecipients = recipients != null ? recipients : List.of();
        log.info("[UserPreferenceService] saveSummaryRecipients start, userId={}, count={}",
                userId, safeRecipients.size());
        try {
            String json = objectMapper.writeValueAsString(safeRecipients);
            userMapper.updateSummaryRecipients(userId, json);
            log.info("[UserPreferenceService] saveSummaryRecipients end, userId={}, count={}",
                    userId, safeRecipients.size());
        } catch (Exception e) {
            log.error("[UserPreferenceService] saveSummaryRecipients failed, userId={}", userId, e);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "保存摘要收件人设置失败");
        }
    }

    public UserSummaryRequirementsVo getSummaryRequirements(Long userId) {
        log.info("[UserPreferenceService] getSummaryRequirements start, userId={}", userId);
        SiUser user = userMapper.findById(userId);
        UserSummaryRequirementsVo result = UserSummaryRequirementsVo.builder()
                .meetingSummaryRequirements(emptyIfNull(user != null ? user.getMeetingSummaryRequirements() : null))
                .speakerSummaryRequirements(emptyIfNull(user != null ? user.getSpeakerSummaryRequirements() : null))
                .build();
        log.info("[UserPreferenceService] getSummaryRequirements end, userId={}, meetingLen={}, speakerLen={}",
                userId,
                result.getMeetingSummaryRequirements().length(),
                result.getSpeakerSummaryRequirements().length());
        return result;
    }

    public UserSummaryRequirementsVo saveSummaryRequirements(
            Long userId,
            SaveUserSummaryRequirementsRequest request
    ) {
        String meetingRequirements = request != null ? request.getMeetingSummaryRequirements() : null;
        String speakerRequirements = request != null ? request.getSpeakerSummaryRequirements() : null;
        log.info("[UserPreferenceService] saveSummaryRequirements start, userId={}, meetingProvided={}, speakerProvided={}",
                userId, meetingRequirements != null, speakerRequirements != null);
        try {
            userMapper.updateSummaryRequirements(userId, meetingRequirements, speakerRequirements);
            UserSummaryRequirementsVo result = getSummaryRequirements(userId);
            log.info("[UserPreferenceService] saveSummaryRequirements end, userId={}", userId);
            return result;
        } catch (Exception e) {
            log.error("[UserPreferenceService] saveSummaryRequirements failed, userId={}", userId, e);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "保存摘要提示词设置失败");
        }
    }

    private static String emptyIfNull(String value) {
        return value != null ? value : "";
    }
}
