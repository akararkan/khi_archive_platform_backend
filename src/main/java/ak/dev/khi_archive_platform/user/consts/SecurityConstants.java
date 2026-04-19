package ak.dev.khi_archive_platform.user.consts;


public class SecurityConstants {
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String HEADER_STRING = "Authorization";
    public static final String TOKEN_CANNOT_BE_VERIFIED= "Token can not be verified";
    public static final String AKAR_ARKAN = "Akar Dev";
    public static final String AKAR_ARKAN_ADMINISTRATION = "User Management By Akar Arkan Rasul";
    public static final String  AUTHORITIES = "authorities";
    public static final String FORBIDDEN_MESSAGE = "You need to log in to access this page";
    public static final String ACCESS_DENIED_MESSAGE = "You do not have permission to access this page";
    public static final String OPTIONS_HTTP_METHOD = "OPTIONS";
    public static final String ID_CLAIM = "id";
    public static final String ROLE = "ROLE";
    public static final String[] PUBLIC_URL = {
        "/api/auth/admin/register",
        "/api/auth/admin/login",
        "/api/auth/admin/reset-password"
    };

    // ── Account locking ───────────────────────────────────────────────────────
    /** Number of failed login attempts before the account is locked. */
    public static final int  MAX_FAILED_ATTEMPTS   = 5;
    /** How long (minutes) the account stays locked before auto-unlock. */
    public static final long LOCK_DURATION_MINUTES = 1;

}