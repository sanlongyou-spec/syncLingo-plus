package com.si.backend.security.authorization;

import com.si.backend.security.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 角色→功能权限映射:ADMIN 管理、OPERATOR 业务、VIEWER 只读、null fail-closed。
 */
class RolePermissionsTest {

    @Test
    void admin_grantsOnlyManagementCapabilities() {
        assertTrue(RolePermissions.grants(Role.ADMIN, PermissionCode.USER_MANAGE));
        assertTrue(RolePermissions.grants(Role.ADMIN, PermissionCode.AUDIT_READ));
        assertTrue(RolePermissions.grants(Role.ADMIN, PermissionCode.OPS_EXECUTE));
        assertTrue(RolePermissions.grants(Role.ADMIN, PermissionCode.ACCOUNT_SELF));
        assertFalse(RolePermissions.grants(Role.ADMIN, PermissionCode.INTERPRETATION_OPERATE), "ADMIN 不应进入同传业务");
        assertFalse(RolePermissions.grants(Role.ADMIN, PermissionCode.MEETING_MANAGE), "ADMIN 不应拥有会议业务权限");
        assertFalse(RolePermissions.grants(Role.ADMIN, PermissionCode.TERMINOLOGY_MANAGE), "ADMIN 不应维护业务术语");
    }

    @Test
    void operator_hasBusinessButNotOps() {
        assertTrue(RolePermissions.grants(Role.OPERATOR, PermissionCode.MEETING_MANAGE));
        assertTrue(RolePermissions.grants(Role.OPERATOR, PermissionCode.INTERPRETATION_OPERATE));
        assertTrue(RolePermissions.grants(Role.OPERATOR, PermissionCode.BOT_OPERATE));
        assertTrue(RolePermissions.grants(Role.OPERATOR, PermissionCode.VOICE_MANAGE));
        assertFalse(RolePermissions.grants(Role.OPERATOR, PermissionCode.OPS_EXECUTE), "OPERATOR 不应有运维权");
    }

    @Test
    void viewer_isReadOnly() {
        assertTrue(RolePermissions.grants(Role.VIEWER, PermissionCode.COST_READ_SELF));
        assertTrue(RolePermissions.grants(Role.VIEWER, PermissionCode.LANGUAGE_PREFERENCE_MANAGE));
        assertFalse(RolePermissions.grants(Role.VIEWER, PermissionCode.MEETING_MANAGE), "VIEWER 不能管理会议");
        assertFalse(RolePermissions.grants(Role.VIEWER, PermissionCode.INTERPRETATION_OPERATE), "VIEWER 不能操作同传");
        assertFalse(RolePermissions.grants(Role.VIEWER, PermissionCode.VOICE_MANAGE), "VIEWER 不能克隆音色");
        assertFalse(RolePermissions.grants(Role.VIEWER, PermissionCode.OPS_EXECUTE));
    }

    @Test
    void nullRole_grantsNothing() {
        assertFalse(RolePermissions.grants(null, PermissionCode.COST_READ_SELF));
        assertTrue(RolePermissions.of(null).isEmpty());
    }

    @Test
    void roleParsing_isLenient() {
        org.junit.jupiter.api.Assertions.assertEquals(Role.ADMIN, Role.from("admin"));
        org.junit.jupiter.api.Assertions.assertEquals(Role.OPERATOR, Role.from(" Operator "));
        org.junit.jupiter.api.Assertions.assertEquals(null, Role.from("bogus"));
        org.junit.jupiter.api.Assertions.assertEquals(null, Role.from(null));
    }
}
