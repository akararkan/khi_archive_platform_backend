package ak.dev.khi_archive_platform.user.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import ak.dev.khi_archive_platform.user.enums.Role;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import ak.dev.khi_archive_platform.user.consts.SecurityConstants;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(
        name = "users_tbl",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_username", columnNames = "username"),
                @UniqueConstraint(name = "uk_users_email", columnNames = "email")
        }
)
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class User implements Serializable, UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("Primary key of the user record")
    private Long userId;

    @Column(name = "name", nullable = false, length = 120)
    @Comment("Full display name of the user")
    private String name;

    @Column(name = "profile_image" , length = 500)
    @Comment("Stored profile image URL or path")
    private String profileImage;

    @Column(name = "username", nullable = false, unique = true, length = 80)
    @Comment("Unique login name used for authentication")
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 160)
    @Comment("Unique email address used for login and notifications")
    private String email;

    @JsonIgnore
    @Column(name = "password", nullable = false, length = 255)
    @Comment("Encrypted account password")
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    @Comment("Assigned application role for authorization")
    private Role role;

    /**
     * Per-user permissions granted by an admin on top of the role's defaults.
     * Effective authorities = role's authorities ∪ this set. Stored eager so
     * the JWT filter doesn't trigger a second query while authorising every
     * request — the set is small (at most a few dozen strings per user).
     */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_permissions",
            joinColumns = @JoinColumn(name = "user_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_user_permissions_user_perm",
                    columnNames = {"user_id", "permission"}))
    @Column(name = "permission", length = 100, nullable = false)
    @Comment("Extra permissions granted to this user beyond the role's defaults")
    private Set<String> extraPermissions = new HashSet<>();

    @Column(name = "is_activated", nullable = false)
    @Builder.Default
    @Comment("Whether the account is active and allowed to sign in")
    private Boolean isActivated = true;

    // ===== Audit =====
    @Column(name = "created_at")
    @Comment("Timestamp when the user record was created")
    private Instant createdAt;

    @Column(name = "updated_at")
    @Comment("Timestamp when the user record was last updated")
    private Instant updatedAt;

    // ===== Locking =====
    @Column(name = "failed_attempts", nullable = false)
    @Builder.Default
    @Comment("Number of failed login attempts")
    private int failedAttempts = 0;

    @JsonIgnore
    @Column(name = "lock_time")
    @Comment("Time when the account was locked")
    private Instant lockTime;

    @Column(name = "is_locked", nullable = false)
    @Builder.Default
    @Comment("Whether the account is currently locked")
    private Boolean isLocked = false;

    // ===== Password expiry =====
    @Column(name = "password_expiry_date")
    @Comment("Date and time when the password expires")
    private Instant passwordExpiryDate;

    // ===== Account Source =====
    // Tracks which account source was used (e.g. "local")
    @Column(name = "provider", length = 30)
    @Comment("Source used to create the account, such as local sign-up")
    private String provider;

    // The unique ID returned by the account source
    @Column(name = "provider_id", length = 120)
    @Comment("External account identifier from the source provider")
    private String providerId;

    // Profile picture URL from an external account source
    @Column(name = "image_url", length = 500)
    @Comment("Profile image URL provided by the external account source")
    private String imageUrl;

    /**
     * Seed the per-role default permissions into {@link #extraPermissions}
     * <b>only when the set is currently empty</b> — so an admin who has
     * already curated this user's perms is never overwritten. Called on
     * first creation as EMPLOYEE and on role transitions into EMPLOYEE.
     * No-op for GUEST (no defaults) and ADMIN (authorities flow from role).
     */
    public void applyRoleDefaults() {
        if (role == null) return;
        Set<String> defaults = Role.defaultExtraPermissions(role);
        if (defaults.isEmpty()) return;
        if (extraPermissions == null) extraPermissions = new HashSet<>();
        if (extraPermissions.isEmpty()) extraPermissions.addAll(defaults);
    }

    // ---------------- UserDetails ----------------

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>(role.getAuthorities());
        if (extraPermissions != null) {
            for (String permission : extraPermissions) {
                if (permission != null && !permission.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(permission));
                }
            }
        }
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        if (Boolean.TRUE.equals(isLocked) && lockTime != null) {
            long lockMs = Instant.now().toEpochMilli() - lockTime.toEpochMilli();
            return lockMs > (SecurityConstants.LOCK_DURATION_MINUTES * 60 * 1000);
        }
        return !Boolean.TRUE.equals(isLocked);
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return !isPasswordExpired();
    }

    @Override
    public boolean isEnabled() {
        return Boolean.TRUE.equals(isActivated);
    }

    public boolean isPasswordExpired() {
        return passwordExpiryDate != null && passwordExpiryDate.isBefore(Instant.now());
    }
}