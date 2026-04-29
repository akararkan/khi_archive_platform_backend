package ak.dev.khi_archive_platform.user.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * User-account management permissions — kept separate from
 * {@link Permission} so application-resource RBAC and account
 * RBAC can evolve independently.
 *
 * Actions:
 *   read   — view user listings or another user's profile
 *   create — provision a new user (admin only)
 *   update — change role, activation, etc. (admin only)
 *   remove — soft-deactivate an account
 *   delete — hard delete an account
 */
@Getter
@RequiredArgsConstructor
public enum UserPermission {

    USER_READ("user:read"),
    USER_CREATE("user:create"),
    USER_UPDATE("user:update"),
    USER_REMOVE("user:remove"),
    USER_DELETE("user:delete");

    private final String permission;
}
