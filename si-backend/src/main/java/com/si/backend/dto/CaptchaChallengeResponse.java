package com.si.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CaptchaChallengeResponse {

    private String captchaId;

    private String question;

    private long expiresInSeconds;
}
