package ak.dev.khi_archive_platform.user.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import ak.dev.khi_archive_platform.user.enums.Role;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import ak.dev.khi_archive_platform.user.consts.SecurityConstants;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;

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


    @Column(name = "is_activated", nullable = false)
    @Builder.Default
    @Comment("Whether the account is active and allowed to sign in")
    private Boolean isActivated = true;

    // ===== Password Reset =====
    @JsonIgnore
    @Column(name = "reset_token", length = 120)
    @Comment("Temporary token used for password reset flow")
    private String resetToken;

    @JsonIgnore
    @Column(name = "reset_token_expiration")
    @Comment("Expiration time of the password reset token")
    private Instant resetTokenExpiration;

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

    // ---------------- UserDetails ----------------

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return role.getAuthorities();
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