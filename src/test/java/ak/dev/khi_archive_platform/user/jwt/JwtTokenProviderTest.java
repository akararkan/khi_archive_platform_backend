package ak.dev.khi_archive_platform.user.jwt;

import ak.dev.khi_archive_platform.user.repo.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "a-stable-secret-that-is-long-enough-for-hmac256";

    @Test
    void blankSecretFailsFastInsteadOfRejectingEveryTokenLater() {
        assertThatThrownBy(() -> providerWithSecret("   ").initSigningKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void fingerprintIdentifiesTheKeyWithoutLeakingIt() {
        JwtTokenProvider provider = providerWithSecret(SECRET);
        provider.initSigningKey();

        JwtTokenProvider sameKey = providerWithSecret(SECRET);
        sameKey.initSigningKey();

        JwtTokenProvider rotatedKey = providerWithSecret(SECRET + "-rotated");
        rotatedKey.initSigningKey();

        assertThat(provider.getSecretFingerprint())
                .hasSize(12)
                .isEqualTo(sameKey.getSecretFingerprint())
                .isNotEqualTo(rotatedKey.getSecretFingerprint())
                .doesNotContain(SECRET);
    }

    private JwtTokenProvider providerWithSecret(String secret) {
        // The session repository is untouched by key initialisation.
        JwtTokenProvider provider = new JwtTokenProvider((SessionRepository) null);
        ReflectionTestUtils.setField(provider, "secret", secret);
        ReflectionTestUtils.setField(provider, "expirationTime", 259_200_000L);
        return provider;
    }
}
