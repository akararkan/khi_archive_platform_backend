package ak.dev.khi_archive_platform.platform.service.person;

import ak.dev.khi_archive_platform.platform.dto.person.PersonResponseDTO;
import ak.dev.khi_archive_platform.platform.repo.person.PersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side cache for active persons. Cached as DTOs (not entities) so
 * Hibernate session state is irrelevant on cache hit.
 *
 * Mutation paths must call {@link #evictAll()} to keep the cache consistent.
 */
@Component
@RequiredArgsConstructor
public class PersonReadCache {

    static final String ACTIVE_CACHE = "persons:all";

    private final PersonRepository personRepository;

    @Cacheable(ACTIVE_CACHE)
    @Transactional(readOnly = true)
    public List<PersonResponseDTO> getAllActive() {
        return personRepository.findAllActiveWithPersonType().stream()
                .map(PersonMapper::toResponse)
                .toList();
    }

    @CacheEvict(value = ACTIVE_CACHE, allEntries = true)
    public void evictAll() {
        // Evict all entries; called after any person mutation.
    }
}
