package com.si.backend.security.authorization;

import com.si.backend.security.Role;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色→功能权限映射(P1,MVP)。
 *
 * <p>仅决定"功能权限"(能不能执行某操作);**数据范围/内容归属由 ResourceOwnershipPolicy 与
 * (P3)support_access_grant 另行约束**。例如 ADMIN 在功能层拥有 MEETING_CONTENT_MANAGE,
 * 但默认仍读不到他人会议正文——那是数据层的事,不在本映射。
 *
 * <p>公共/服务/系统类权限码(AUTH_LOGIN、HEALTH_READ、BOT_*、SHARE_READ、INTERNAL_ASYNC 等)
 * 由 IdentityType 而非用户角色裁决,不在此映射的语义内(即便 ADMIN 全集包含它们也不影响)。
 *
 * <p>枚举值随 PermissionCode 演进时:ADMIN 自动获得全部;OPERATOR/VIEWER 为显式白名单,新增码默认不授予。
 */
public final class RolePermissions {

    private RolePermissions() {
    }

    /** OPERATOR:会议准备、同传、摘要、通知及本人配置(不含全局运维)。 */
    private static final Set<PermissionCode> OPERATOR_CODES = EnumSet.of(
            PermissionCode.MEETING_MANAGE,
            PermissionCode.MEETING_CONTENT_MANAGE,
            PermissionCode.INTERPRETATION_OPERATE,
            PermissionCode.TERMINOLOGY_MANAGE,
            PermissionCode.HOTWORD_MANAGE,
            PermissionCode.AUDIO_MANAGE,
            PermissionCode.SUMMARY_MANAGE,
            PermissionCode.PRE_MEETING_MANAGE,
            PermissionCode.BOT_OPERATE,
            PermissionCode.TEAMS_SEND,
            PermissionCode.PARTICIPANT_READ,
            PermissionCode.DIRECTORY_ACCESS,
            PermissionCode.USER_PREFERENCE_MANAGE,
            PermissionCode.LANGUAGE_PREFERENCE_MANAGE,
            PermissionCode.TRANSLATE_USE,
            PermissionCode.COST_READ_SELF,
            PermissionCode.COST_RATES_READ,
            PermissionCode.ACCOUNT_SELF);

    /** VIEWER:只读/本人账号级(业务写入一律不授;查看被分配会议属 P3 meeting_member)。 */
    private static final Set<PermissionCode> VIEWER_CODES = EnumSet.of(
            PermissionCode.USER_PREFERENCE_MANAGE,
            PermissionCode.LANGUAGE_PREFERENCE_MANAGE,
            PermissionCode.COST_READ_SELF,
            PermissionCode.COST_RATES_READ,
            PermissionCode.TRANSLATE_USE,
            PermissionCode.ACCOUNT_SELF);

    private static final Map<Role, Set<PermissionCode>> BY_ROLE = new EnumMap<>(Role.class);

    static {
        BY_ROLE.put(Role.ADMIN, EnumSet.allOf(PermissionCode.class));
        BY_ROLE.put(Role.OPERATOR, EnumSet.copyOf(OPERATOR_CODES));
        BY_ROLE.put(Role.VIEWER, EnumSet.copyOf(VIEWER_CODES));
    }

    public static Set<PermissionCode> of(Role role) {
        if (role == null) {
            return Set.of();
        }
        return BY_ROLE.getOrDefault(role, Set.of());
    }

    /** 该角色是否被授予该功能权限码。role 或 code 为 null → false(fail-closed)。 */
    public static boolean grants(Role role, PermissionCode code) {
        return role != null && code != null && of(role).contains(code);
    }
}
