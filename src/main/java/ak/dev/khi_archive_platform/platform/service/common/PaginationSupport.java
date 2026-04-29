package ak.dev.khi_archive_platform.platform.service.common;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Slices an in-memory list into a {@link Page} for endpoints whose data
 * source is the Redis read-cache (full active set already in memory).
 *
 * Out-of-range pages return an empty content list with the correct total,
 * matching Spring Data's repository {@code findAll(Pageable)} semantics.
 */
public final class PaginationSupport {

    private PaginationSupport() {}

    public static <T> Page<T> sliceList(List<T> all, Pageable pageable) {
        if (all == null) all = List.of();
        int total = all.size();

        if (pageable.isUnpaged()) {
            return new PageImpl<>(all, pageable, total);
        }

        int start = (int) Math.min(pageable.getOffset(), total);
        int end   = Math.min(start + pageable.getPageSize(), total);
        List<T> slice = (start >= total) ? List.of() : all.subList(start, end);
        return new PageImpl<>(slice, pageable, total);
    }
}
