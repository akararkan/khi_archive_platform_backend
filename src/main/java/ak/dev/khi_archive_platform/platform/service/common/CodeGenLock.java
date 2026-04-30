package ak.dev.khi_archive_platform.platform.service.common;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Postgres advisory-lock helper for serialising concurrent code generation.
 *
 * <p>Why: code-generation paths read {@code count(...) + 1} then INSERT. Two
 * concurrent transactions can read the same count, generate the same code,
 * and one will fail at flush with a unique-constraint violation. With ~8
 * employees creating records simultaneously this is plausible — especially
 * for media uploads under the same Project.
 *
 * <p>Mechanism: {@code pg_advisory_xact_lock(key)} acquires an exclusive
 * lock on a 64-bit key for the duration of the current transaction. Other
 * transactions that request the same key block until the holder commits or
 * rolls back. Different keys proceed in parallel — so two creates against
 * different Projects never block each other; two creates against the same
 * Project serialise.
 *
 * <p>Lock keys are derived deterministically from a string namespace via
 * UUID hashing, so callers don't need to manage numeric ids — they pass the
 * natural identifier (a project id, a code prefix, etc.) and the helper
 * produces a stable long.
 */
@Component
@RequiredArgsConstructor
public class CodeGenLock {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Acquire an exclusive transaction-scoped advisory lock keyed by {@code namespace}.
     * Must be called inside a transaction. Returns once the lock is held or
     * blocks until it can be acquired.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String namespace) {
        long key = toAdvisoryKey(namespace);
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, key);
    }

    /** Stable 64-bit hash of a string. UUID v3 (MD5-based) gives good distribution. */
    private static long toAdvisoryKey(String namespace) {
        return UUID.nameUUIDFromBytes(namespace.getBytes(StandardCharsets.UTF_8))
                .getMostSignificantBits();
    }
}
