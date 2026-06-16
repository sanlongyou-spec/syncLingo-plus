package com.si.backend.service;

import com.si.backend.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P5 登录限流:ip+账号超阈退避(429)、成功清零、ip 维跨账号撞库、不同 ip/账号互不影响。
 */
class LoginThrottleServiceTest {

    private final LoginThrottleService service = new LoginThrottleService();
    private static final String IP = "203.0.113.7";

    @Test
    void underThreshold_isNotBlocked() {
        for (int i = 0; i < LoginThrottleService.USER_IP_FREE_ATTEMPTS; i++) {
            service.onFailedAttempt(IP, "alice");
        }
        assertDoesNotThrow(() -> service.assertNotBlocked(IP, "alice"));
    }

    @Test
    void captchaSoftThreshold_precedesHardBlock() {
        for (int i = 0; i < LoginThrottleService.USER_IP_CAPTCHA_ATTEMPTS; i++) {
            service.onFailedAttempt(IP, "alice");
        }
        assertEquals(true, service.requiresCaptcha(IP, "alice"));
        assertDoesNotThrow(() -> service.assertNotBlocked(IP, "alice"));
    }

    @Test
    void overThreshold_sameIpAndUser_isBlocked() {
        for (int i = 0; i < LoginThrottleService.USER_IP_FREE_ATTEMPTS + 1; i++) {
            service.onFailedAttempt(IP, "alice");
        }
        assertEquals(429, assertThrows(BizException.class,
                () -> service.assertNotBlocked(IP, "alice")).getCode());
    }

    @Test
    void successfulLogin_resetsUserIpCounter() {
        for (int i = 0; i < LoginThrottleService.USER_IP_FREE_ATTEMPTS; i++) {
            service.onFailedAttempt(IP, "alice");
        }
        service.onSuccessfulLogin(IP, "alice");
        // 计数清零后,再一次失败不应触发退避。
        service.onFailedAttempt(IP, "alice");
        assertDoesNotThrow(() -> service.assertNotBlocked(IP, "alice"));
    }

    @Test
    void ipDimension_blocksCrossAccountBruteForce() {
        // 同源对不同账号各失败一次,均不触发账号维退避,但累积触发 ip 维退避。
        for (int i = 0; i <= LoginThrottleService.IP_FREE_ATTEMPTS; i++) {
            service.onFailedAttempt(IP, "user" + i);
        }
        // 即便是从未失败过的新账号,因 ip 维退避也被拒绝。
        assertEquals(429, assertThrows(BizException.class,
                () -> service.assertNotBlocked(IP, "brand-new-user")).getCode());
    }

    @Test
    void differentIp_isIndependent() {
        for (int i = 0; i < LoginThrottleService.USER_IP_FREE_ATTEMPTS + 2; i++) {
            service.onFailedAttempt(IP, "alice");
        }
        // 另一来源 IP 不受影响。
        assertDoesNotThrow(() -> service.assertNotBlocked("198.51.100.9", "alice"));
    }

    @Test
    void caseInsensitiveUsername_sharesCounter() {
        for (int i = 0; i < LoginThrottleService.USER_IP_FREE_ATTEMPTS + 1; i++) {
            service.onFailedAttempt(IP, "Alice");
        }
        assertEquals(429, assertThrows(BizException.class,
                () -> service.assertNotBlocked(IP, "alice")).getCode());
    }
}
