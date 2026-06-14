package com.si.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.mapper.UserMapper;
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
        try {
            jdbcTemplate.execute("ALTER TABLE si_user ADD COLUMN summary_recipients TEXT DEFAULT NULL");
            log.info("[UserPreferenceService] summary_recipients column added to si_user");
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate column")) {
                log.debug("[UserPreferenceService] summary_recipients column already exists");
            } else {
                log.warn("[UserPreferenceService] failed to add summary_recipients column: {}", e.getMessage());
            }
        }
    }

    public List<String> getSummaryRecipients(Long userId) {
        String json = userMapper.getSummaryRecipients(userId);
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[UserPreferenceService] failed to parse summary_recipients for userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    public void saveSummaryRecipients(Long userId, List<String> recipients) {
        try {
            String json = objectMapper.writeValueAsString(recipients);
            userMapper.updateSummaryRecipients(userId, json);
            log.info("[UserPreferenceService] saved summary recipients, userId={}, count={}", userId, recipients.size());
        } catch (Exception e) {
            log.warn("[UserPreferenceService] failed to save summary_recipients for userId={}: {}", userId, e.getMessage());
        }
    }
}
