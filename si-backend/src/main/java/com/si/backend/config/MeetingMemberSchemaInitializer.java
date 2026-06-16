package com.si.backend.config;

import com.si.backend.mapper.MeetingMemberMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P3:启动期建 meeting_member 表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingMemberSchemaInitializer {

    private final MeetingMemberMapper meetingMemberMapper;

    @PostConstruct
    public void init() {
        try {
            meetingMemberMapper.createTableIfNotExists();
            log.info("[MeetingMemberSchemaInitializer] meeting_member table ready");
        } catch (Exception e) {
            log.warn("[MeetingMemberSchemaInitializer] create meeting_member failed: {}", e.getMessage());
        }
    }
}
