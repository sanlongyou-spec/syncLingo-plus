package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.UserVoice;
import com.si.backend.integration.CartesiaVoiceCloneIntegration;
import com.si.backend.mapper.UserVoiceMapper;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.vo.CloneVoiceResponse;
import com.si.backend.vo.UserVoiceVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Manages per-user cloned voices and validates that manual TTS voice selection belongs to the actor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserVoiceService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_VOICE_NAME_CHARS = 50;
    private static final int MIN_AUDIO_SECONDS = 1;
    private static final int DEFAULT_DURATION_SECONDS = 0;
    private static final String DEFAULT_SCOPE = "SELF";
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            Constants.LANG_CLONE_ZH,
            Constants.LANG_CLONE_ID,
            Constants.LANG_CLONE_EN
    );

    private final UserVoiceMapper userVoiceMapper;
    private final CartesiaVoiceCloneIntegration cartesiaVoiceCloneIntegration;

    @PostConstruct
    public void initTable() {
        log.info("[UserVoiceService] initTable start");
        userVoiceMapper.createTableIfNotExists();
        addColumnIfMissing("authorized", userVoiceMapper::addAuthorizedColumnIfNotExists);
        addColumnIfMissing("scope", userVoiceMapper::addScopeColumnIfNotExists);
        addColumnIfMissing("disabled", userVoiceMapper::addDisabledColumnIfNotExists);
        log.info("[UserVoiceService] initTable end");
    }

    public List<UserVoiceVo> listVoices(AuthenticatedActor actor) {
        log.info("[UserVoiceService] listVoices start, userId={}", actor.userId());
        List<UserVoiceVo> voices = userVoiceMapper.findEnabledByUserId(actor.userId()).stream()
                .filter(this::isUsable)
                .map(this::toVo)
                .toList();
        log.info("[UserVoiceService] listVoices end, userId={}, count={}", actor.userId(), voices.size());
        return voices;
    }

    public CloneVoiceResponse cloneVoice(
            AuthenticatedActor actor,
            MultipartFile audio,
            String voiceName,
            String language,
            Integer durationSeconds
    ) throws IOException {
        String normalizedName = normalizeVoiceName(voiceName);
        String normalizedLanguage = normalizeLanguage(language);
        int normalizedDuration = normalizeDuration(durationSeconds);
        log.info("[UserVoiceService] cloneVoice start, userId={}, voiceName={}, language={}, durationSeconds={}, fileName={}, size={}",
                actor.userId(), normalizedName, normalizedLanguage, normalizedDuration,
                audio == null ? null : audio.getOriginalFilename(), audio == null ? 0 : audio.getSize());
        if (audio == null || audio.isEmpty()) {
            throw BizException.of(ErrorCode.VOICE_SAMPLE_INVALID, "请先录制音频样本");
        }
        if (normalizedDuration > 0 && normalizedDuration < MIN_AUDIO_SECONDS) {
            throw BizException.of(ErrorCode.VOICE_SAMPLE_TOO_SHORT, "音频样本至少需要 1 秒");
        }
        byte[] bytes = audio.getBytes();
        if (bytes.length < Constants.MIN_AUDIO_SAMPLE_BYTES && normalizedDuration == DEFAULT_DURATION_SECONDS) {
            throw BizException.of(ErrorCode.VOICE_SAMPLE_TOO_SHORT, "音频样本太短，请重新录制");
        }
        CartesiaVoiceCloneIntegration.CloneResult cloneResult = cartesiaVoiceCloneIntegration.cloneVoice(
                normalizedName,
                normalizedLanguage,
                bytes,
                audio.getOriginalFilename(),
                audio.getContentType()
        );
        UserVoice entity = new UserVoice();
        entity.setUserId(actor.userId());
        entity.setVoiceId(cloneResult.voiceId());
        entity.setVoiceName(normalizedName);
        entity.setDurationSeconds(normalizedDuration);
        entity.setAuthorized(true);
        entity.setScope(DEFAULT_SCOPE);
        entity.setDisabled(false);
        userVoiceMapper.insert(entity);
        log.info("[UserVoiceService] cloneVoice end, userId={}, dbId={}, voiceId={}",
                actor.userId(), entity.getId(), entity.getVoiceId());
        return CloneVoiceResponse.builder()
                .voiceId(entity.getVoiceId())
                .voiceName(entity.getVoiceName())
                .durationSeconds(entity.getDurationSeconds())
                .createTime(entity.getCreateTime() == null ? null : entity.getCreateTime().format(DT_FMT))
                .build();
    }

    public void deleteVoice(AuthenticatedActor actor, String voiceId) {
        String normalized = normalizeVoiceId(voiceId);
        log.info("[UserVoiceService] deleteVoice start, userId={}, voiceId={}", actor.userId(), normalized);
        int rows = userVoiceMapper.deleteByUserIdAndVoiceId(actor.userId(), normalized);
        if (rows == 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "音色不存在");
        }
        log.info("[UserVoiceService] deleteVoice end, userId={}, voiceId={}, rows={}",
                actor.userId(), normalized, rows);
    }

    public String requireUsableVoice(Long userId, String voiceId) {
        String normalized = normalizeVoiceId(voiceId);
        UserVoice voice = userVoiceMapper.findByUserIdAndVoiceId(userId, normalized);
        if (!isUsable(voice)) {
            throw BizException.of(ErrorCode.FORBIDDEN, "无权使用该音色");
        }
        log.info("[UserVoiceService] requireUsableVoice ok, userId={}, voiceId={}", userId, normalized);
        return normalized;
    }

    private void addColumnIfMissing(String column, Runnable ddl) {
        try {
            ddl.run();
            log.info("[UserVoiceService] column added: {}", column);
        } catch (DataAccessException e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate column")) {
                log.info("[UserVoiceService] column already exists: {}", column);
            } else {
                throw e;
            }
        }
    }

    private boolean isUsable(UserVoice voice) {
        return voice != null
                && Boolean.TRUE.equals(voice.getAuthorized())
                && !Boolean.TRUE.equals(voice.getDisabled())
                && voice.getVoiceId() != null
                && !voice.getVoiceId().isBlank();
    }

    private String normalizeVoiceName(String voiceName) {
        String normalized = voiceName == null ? "" : voiceName.trim();
        if (normalized.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "音色名称不能为空");
        }
        if (normalized.length() > MAX_VOICE_NAME_CHARS) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "音色名称不能超过 50 个字");
        }
        return normalized;
    }

    private String normalizeLanguage(String language) {
        String normalized = language == null || language.isBlank()
                ? Constants.LANG_CLONE_ZH
                : language.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "音色语言仅支持中文、印尼语或英语");
        }
        return normalized;
    }

    private int normalizeDuration(Integer durationSeconds) {
        if (durationSeconds == null) {
            return DEFAULT_DURATION_SECONDS;
        }
        return Math.max(DEFAULT_DURATION_SECONDS, durationSeconds);
    }

    private String normalizeVoiceId(String voiceId) {
        String normalized = voiceId == null ? "" : voiceId.trim();
        if (normalized.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "voiceId 不能为空");
        }
        return normalized;
    }

    private UserVoiceVo toVo(UserVoice voice) {
        return UserVoiceVo.builder()
                .id(voice.getId())
                .userId(voice.getUserId())
                .voiceId(voice.getVoiceId())
                .voiceName(voice.getVoiceName())
                .durationSeconds(voice.getDurationSeconds())
                .authorized(voice.getAuthorized())
                .scope(voice.getScope())
                .disabled(voice.getDisabled())
                .createTime(voice.getCreateTime() == null ? null : voice.getCreateTime().format(DT_FMT))
                .updateTime(voice.getUpdateTime() == null ? null : voice.getUpdateTime().format(DT_FMT))
                .build();
    }
}
