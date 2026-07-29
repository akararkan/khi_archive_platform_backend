package ak.dev.khi_archive_platform.platform.service.items;

import ak.dev.khi_archive_platform.platform.dto.audio.AudioResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.image.ImageResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.items.ItemDTO;
import ak.dev.khi_archive_platform.platform.dto.items.ItemFilterParams;
import ak.dev.khi_archive_platform.platform.dto.items.ItemType;
import ak.dev.khi_archive_platform.platform.service.common.ArchiveTime;
import ak.dev.khi_archive_platform.platform.dto.text.TextResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.video.VideoResponseDTO;
import ak.dev.khi_archive_platform.platform.service.audio.AudioReadCache;
import ak.dev.khi_archive_platform.platform.service.common.PaginationSupport;
import ak.dev.khi_archive_platform.platform.service.image.ImageReadCache;
import ak.dev.khi_archive_platform.platform.service.text.TextReadCache;
import ak.dev.khi_archive_platform.platform.service.video.VideoReadCache;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Unified GET /api/items endpoint backend.
 *
 * <h3>Algorithm — fastest path that still respects pagination semantics:</h3>
 * <ol>
 *   <li><b>Cache reads only.</b> Pulls each media's already-built
 *       {@link AudioResponseDTO}/etc. list from the per-entity Redis read-cache
 *       (no DB round-trip when warm; first miss runs the underlying
 *       {@code findAllByRemovedAtIsNull()} on each table — one query each,
 *       batched fetch of associations). The four reads run sequentially but
 *       could be parallelized if profiling shows benefit.</li>
 *   <li><b>Whole-bucket short-circuit.</b> If the caller supplied
 *       {@code types}, buckets outside that set are skipped entirely — no
 *       cache read, no mapping work. This makes "give me audios only" cost
 *       the same as the AudioService /getAll path.</li>
 *   <li><b>Project-keyed pre-normalization.</b> Every set filter
 *       ({@code projectCodes}, {@code personCodes}, {@code categoryCodes},
 *       {@code languages}) is lower-cased into a {@link HashSet} once, before
 *       the loop. Every row check is then O(1).</li>
 *   <li><b>Single linear pass per bucket.</b> Each bucket is filtered with
 *       cheap-first predicates (boolean equals → set membership → date range
 *       → string contains for {@code q}) and mapped to {@link ItemDTO} in the
 *       same loop. No second pass, no re-iteration.</li>
 *   <li><b>One merge + one sort.</b> The four filtered lists are concatenated
 *       and sorted once with a comparator built from {@code sortBy} +
 *       {@code sortDirection}. Default: {@code updatedAt DESC}.</li>
 *   <li><b>In-memory page slice.</b> {@link PaginationSupport#sliceList}
 *       returns the requested page without copying the rest.</li>
 * </ol>
 *
 * <p>For N≈thousands of items across the four caches this completes in
 * microseconds. The dominant cost is the JSON serialization of the response,
 * not the filter/sort.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ItemsService {

    private final AudioReadCache audioReadCache;
    private final VideoReadCache videoReadCache;
    private final ImageReadCache imageReadCache;
    private final TextReadCache textReadCache;

    public Page<ItemDTO> list(ItemFilterParams params, Pageable pageable) {
        ItemFilterParams safe = params == null ? new ItemFilterParams() : params;

        // ── Pre-normalize filter inputs once ────────────────────────────────────
        Set<ItemType> wantedTypes = normalizeTypes(safe.getTypes());
        String qLower = lower(safe.getQ());
        Set<String> projectCodes = normalizeLower(safe.getProjectCodes());
        Set<String> personCodes = normalizeLower(safe.getPersonCodes());
        Set<String> categoryCodes = normalizeLower(safe.getCategoryCodes());
        Set<String> languages = normalizeLower(safe.getLanguages());

        List<ItemDTO> merged = new ArrayList<>(2048);

        if (wantedTypes.contains(ItemType.AUDIO)) {
            for (AudioResponseDTO a : audioReadCache.getAllActive()) {
                if (!keepAudio(a, qLower, projectCodes, personCodes, categoryCodes, languages, safe)) continue;
                merged.add(toItem(a));
            }
        }
        if (wantedTypes.contains(ItemType.VIDEO)) {
            for (VideoResponseDTO v : videoReadCache.getAllActive()) {
                if (!keepVideo(v, qLower, projectCodes, personCodes, categoryCodes, languages, safe)) continue;
                merged.add(toItem(v));
            }
        }
        if (wantedTypes.contains(ItemType.IMAGE)) {
            for (ImageResponseDTO i : imageReadCache.getAllActive()) {
                if (!keepImage(i, qLower, projectCodes, personCodes, categoryCodes, languages, safe)) continue;
                merged.add(toItem(i));
            }
        }
        if (wantedTypes.contains(ItemType.TEXT)) {
            for (TextResponseDTO t : textReadCache.getAllActive()) {
                if (!keepText(t, qLower, projectCodes, personCodes, categoryCodes, languages, safe)) continue;
                merged.add(toItem(t));
            }
        }

        // ── Sort once across the merged set ─────────────────────────────────────
        Comparator<ItemDTO> comparator = comparatorFor(safe.getSortBy(), safe.getSortDirection());
        if (comparator != null) {
            merged.sort(comparator);
        }

        return PaginationSupport.sliceList(merged, pageable);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Per-bucket keep predicates. Cheap-first ordering.
    // ──────────────────────────────────────────────────────────────────────────

    private static boolean keepAudio(AudioResponseDTO a, String qLower,
                                     Set<String> projectCodes, Set<String> personCodes,
                                     Set<String> categoryCodes, Set<String> languages,
                                     ItemFilterParams p) {
        if (p.getIsPublic() != null && !Objects.equals(p.getIsPublic(), a.getIsPublic())) return false;
        if (p.getProjectVisibleToPublic() != null
                && !Objects.equals(p.getProjectVisibleToPublic(), a.getProjectVisibleToPublic())) return false;
        if (!projectCodes.isEmpty() && !inSet(projectCodes, a.getProjectCode())) return false;
        if (!personCodes.isEmpty() && !inSetOrUntitled(personCodes, a.getPersonCode())) return false;
        if (!categoryCodes.isEmpty() && !anyInSet(categoryCodes, a.getCategoryCodes())) return false;
        if (!languages.isEmpty() && !inSet(languages, a.getLanguage())) return false;
        if (!withinRange(a.getCreatedAt(), p.getCreatedFrom(), p.getCreatedTo())) return false;
        if (!withinRange(a.getUpdatedAt(), p.getUpdatedFrom(), p.getUpdatedTo())) return false;
        if (qLower != null && !matchesAudio(a, qLower)) return false;
        return true;
    }

    private static boolean keepVideo(VideoResponseDTO v, String qLower,
                                     Set<String> projectCodes, Set<String> personCodes,
                                     Set<String> categoryCodes, Set<String> languages,
                                     ItemFilterParams p) {
        if (p.getIsPublic() != null && !Objects.equals(p.getIsPublic(), v.getIsPublic())) return false;
        if (p.getProjectVisibleToPublic() != null
                && !Objects.equals(p.getProjectVisibleToPublic(), v.getProjectVisibleToPublic())) return false;
        if (!projectCodes.isEmpty() && !inSet(projectCodes, v.getProjectCode())) return false;
        if (!personCodes.isEmpty() && !inSetOrUntitled(personCodes, v.getPersonCode())) return false;
        if (!categoryCodes.isEmpty() && !anyInSet(categoryCodes, v.getCategoryCodes())) return false;
        if (!languages.isEmpty() && !inSet(languages, v.getLanguage())) return false;
        if (!withinRange(v.getCreatedAt(), p.getCreatedFrom(), p.getCreatedTo())) return false;
        if (!withinRange(v.getUpdatedAt(), p.getUpdatedFrom(), p.getUpdatedTo())) return false;
        if (qLower != null && !matchesVideo(v, qLower)) return false;
        return true;
    }

    private static boolean keepImage(ImageResponseDTO i, String qLower,
                                     Set<String> projectCodes, Set<String> personCodes,
                                     Set<String> categoryCodes, Set<String> languages,
                                     ItemFilterParams p) {
        if (p.getIsPublic() != null && !Objects.equals(p.getIsPublic(), i.getIsPublic())) return false;
        if (p.getProjectVisibleToPublic() != null
                && !Objects.equals(p.getProjectVisibleToPublic(), i.getProjectVisibleToPublic())) return false;
        if (!projectCodes.isEmpty() && !inSet(projectCodes, i.getProjectCode())) return false;
        if (!personCodes.isEmpty() && !inSetOrUntitled(personCodes, i.getPersonCode())) return false;
        if (!categoryCodes.isEmpty() && !anyInSet(categoryCodes, i.getCategoryCodes())) return false;
        // Image has no language field — fail closed if a language filter is set.
        if (!languages.isEmpty()) return false;
        if (!withinRange(i.getCreatedAt(), p.getCreatedFrom(), p.getCreatedTo())) return false;
        if (!withinRange(i.getUpdatedAt(), p.getUpdatedFrom(), p.getUpdatedTo())) return false;
        if (qLower != null && !matchesImage(i, qLower)) return false;
        return true;
    }

    private static boolean keepText(TextResponseDTO t, String qLower,
                                    Set<String> projectCodes, Set<String> personCodes,
                                    Set<String> categoryCodes, Set<String> languages,
                                    ItemFilterParams p) {
        if (p.getIsPublic() != null && !Objects.equals(p.getIsPublic(), t.getIsPublic())) return false;
        if (p.getProjectVisibleToPublic() != null
                && !Objects.equals(p.getProjectVisibleToPublic(), t.getProjectVisibleToPublic())) return false;
        if (!projectCodes.isEmpty() && !inSet(projectCodes, t.getProjectCode())) return false;
        if (!personCodes.isEmpty() && !inSetOrUntitled(personCodes, t.getPersonCode())) return false;
        if (!categoryCodes.isEmpty() && !anyInSet(categoryCodes, t.getCategoryCodes())) return false;
        if (!languages.isEmpty() && !inSet(languages, t.getLanguage())) return false;
        if (!withinRange(t.getCreatedAt(), p.getCreatedFrom(), p.getCreatedTo())) return false;
        if (!withinRange(t.getUpdatedAt(), p.getUpdatedFrom(), p.getUpdatedTo())) return false;
        if (qLower != null && !matchesText(t, qLower)) return false;
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Free-text {q} match — substring scan across the per-type searchable surface.
    // Cheap-first: business code → title → project → person → tags → keywords →
    // description. Returns on the first hit.
    // ──────────────────────────────────────────────────────────────────────────

    private static boolean matchesAudio(AudioResponseDTO a, String q) {
        return containsAny(q,
                a.getAudioCode(),
                a.getOriginTitle(), a.getAlterTitle(),
                a.getCentralKurdishTitle(), a.getRomanizedTitle(),
                a.getFileName(),
                a.getProjectCode(), a.getProjectName(),
                a.getPersonCode(), a.getPersonName(),
                a.getSpeaker(), a.getComposer(), a.getPoet(), a.getProducer(),
                a.getCity(), a.getRegion(),
                a.getDescription(), a.getAbstractText())
                || containsAnyList(q, a.getTags())
                || containsAnyList(q, a.getKeywords())
                || containsAnyList(q, a.getGenre())
                || containsAnyList(q, a.getCategoryCodes());
    }

    private static boolean matchesVideo(VideoResponseDTO v, String q) {
        return containsAny(q,
                v.getVideoCode(),
                v.getOriginalTitle(), v.getAlternativeTitle(),
                v.getTitleInCentralKurdish(), v.getRomanizedTitle(),
                v.getFileName(),
                v.getProjectCode(), v.getProjectName(),
                v.getPersonCode(), v.getPersonName(),
                v.getCreatorArtistDirector(), v.getProducer(),
                v.getEvent(), v.getLocation(),
                v.getPersonShownInVideo(), v.getDescription())
                || containsAnyList(q, v.getTags())
                || containsAnyList(q, v.getKeywords())
                || containsAnyList(q, v.getSubject())
                || containsAnyList(q, v.getGenre())
                || containsAnyList(q, v.getCategoryCodes());
    }

    private static boolean matchesImage(ImageResponseDTO i, String q) {
        return containsAny(q,
                i.getImageCode(),
                i.getOriginalTitle(), i.getAlternativeTitle(),
                i.getTitleInCentralKurdish(), i.getRomanizedTitle(),
                i.getFileName(),
                i.getProjectCode(), i.getProjectName(),
                i.getPersonCode(), i.getPersonName(),
                i.getCreatorArtistPhotographer(),
                i.getEvent(), i.getLocation(),
                i.getPersonShownInImage(), i.getDescription())
                || containsAnyList(q, i.getTags())
                || containsAnyList(q, i.getKeywords())
                || containsAnyList(q, i.getSubject())
                || containsAnyList(q, i.getGenre())
                || containsAnyList(q, i.getCategoryCodes());
    }

    private static boolean matchesText(TextResponseDTO t, String q) {
        return containsAny(q,
                t.getTextCode(),
                t.getOriginalTitle(), t.getAlternativeTitle(),
                t.getTitleInCentralKurdish(), t.getRomanizedTitle(),
                t.getFileName(),
                t.getProjectCode(), t.getProjectName(),
                t.getPersonCode(), t.getPersonName(),
                t.getAuthor(), t.getDocumentType(),
                t.getDescription())
                || containsAnyList(q, t.getTags())
                || containsAnyList(q, t.getKeywords())
                || containsAnyList(q, t.getSubject())
                || containsAnyList(q, t.getGenre())
                || containsAnyList(q, t.getCategoryCodes());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Type-specific → ItemDTO mappers
    // ──────────────────────────────────────────────────────────────────────────

    private static ItemDTO toItem(AudioResponseDTO a) {
        return ItemDTO.builder()
                .type(ItemType.AUDIO)
                .id(a.getId())
                .code(a.getAudioCode())
                .title(firstNonBlank(a.getOriginTitle(), a.getAlterTitle(),
                        a.getCentralKurdishTitle(), a.getRomanizedTitle(),
                        a.getFileName(), a.getAudioCode()))
                .projectId(a.getProjectId())
                .projectCode(a.getProjectCode())
                .projectName(a.getProjectName())
                .projectVisibleToPublic(a.getProjectVisibleToPublic())
                .personId(a.getPersonId())
                .personCode(a.getPersonCode())
                .personName(a.getPersonName())
                .categoryCodes(a.getCategoryCodes())
                .fileUrl(a.getAudioFileUrl())
                .fileExtension(a.getFileExtension())
                .fileSize(a.getFileSize())
                .language(a.getLanguage())
                .dialect(a.getDialect())
                .tags(a.getTags())
                .keywords(a.getKeywords())
                .isPublic(a.getIsPublic())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .removedAt(a.getRemovedAt())
                .createdBy(a.getCreatedBy())
                .updatedBy(a.getUpdatedBy())
                .removedBy(a.getRemovedBy())
                .audio(a)
                .build();
    }

    private static ItemDTO toItem(VideoResponseDTO v) {
        return ItemDTO.builder()
                .type(ItemType.VIDEO)
                .id(v.getId())
                .code(v.getVideoCode())
                .title(firstNonBlank(v.getOriginalTitle(), v.getAlternativeTitle(),
                        v.getTitleInCentralKurdish(), v.getRomanizedTitle(),
                        v.getFileName(), v.getVideoCode()))
                .projectId(v.getProjectId())
                .projectCode(v.getProjectCode())
                .projectName(v.getProjectName())
                .projectVisibleToPublic(v.getProjectVisibleToPublic())
                .personId(v.getPersonId())
                .personCode(v.getPersonCode())
                .personName(v.getPersonName())
                .categoryCodes(v.getCategoryCodes())
                .fileUrl(v.getVideoFileUrl())
                .fileExtension(v.getExtension())
                .fileSize(v.getFileSize())
                .language(v.getLanguage())
                .dialect(v.getDialect())
                .tags(v.getTags())
                .keywords(v.getKeywords())
                .isPublic(v.getIsPublic())
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .removedAt(v.getRemovedAt())
                .createdBy(v.getCreatedBy())
                .updatedBy(v.getUpdatedBy())
                .removedBy(v.getRemovedBy())
                .video(v)
                .build();
    }

    private static ItemDTO toItem(ImageResponseDTO i) {
        return ItemDTO.builder()
                .type(ItemType.IMAGE)
                .id(i.getId())
                .code(i.getImageCode())
                .title(firstNonBlank(i.getOriginalTitle(), i.getAlternativeTitle(),
                        i.getTitleInCentralKurdish(), i.getRomanizedTitle(),
                        i.getFileName(), i.getImageCode()))
                .projectId(i.getProjectId())
                .projectCode(i.getProjectCode())
                .projectName(i.getProjectName())
                .projectVisibleToPublic(i.getProjectVisibleToPublic())
                .personId(i.getPersonId())
                .personCode(i.getPersonCode())
                .personName(i.getPersonName())
                .categoryCodes(i.getCategoryCodes())
                .fileUrl(i.getImageFileUrl())
                .fileExtension(i.getExtension())
                .fileSize(i.getFileSize())
                // Image has no language field.
                .tags(i.getTags())
                .keywords(i.getKeywords())
                .isPublic(i.getIsPublic())
                .createdAt(i.getCreatedAt())
                .updatedAt(i.getUpdatedAt())
                .removedAt(i.getRemovedAt())
                .createdBy(i.getCreatedBy())
                .updatedBy(i.getUpdatedBy())
                .removedBy(i.getRemovedBy())
                .image(i)
                .build();
    }

    private static ItemDTO toItem(TextResponseDTO t) {
        return ItemDTO.builder()
                .type(ItemType.TEXT)
                .id(t.getId())
                .code(t.getTextCode())
                .title(firstNonBlank(t.getOriginalTitle(), t.getAlternativeTitle(),
                        t.getTitleInCentralKurdish(), t.getRomanizedTitle(),
                        t.getFileName(), t.getTextCode()))
                .projectId(t.getProjectId())
                .projectCode(t.getProjectCode())
                .projectName(t.getProjectName())
                .projectVisibleToPublic(t.getProjectVisibleToPublic())
                .personId(t.getPersonId())
                .personCode(t.getPersonCode())
                .personName(t.getPersonName())
                .categoryCodes(t.getCategoryCodes())
                .fileUrl(t.getTextFileUrl())
                .coverImageUrl(t.getCoverImageUrl())
                .fileExtension(t.getExtension())
                .fileSize(t.getFileSize())
                .language(t.getLanguage())
                .dialect(t.getDialect())
                .tags(t.getTags())
                .keywords(t.getKeywords())
                .isPublic(t.getIsPublic())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .removedAt(t.getRemovedAt())
                .createdBy(t.getCreatedBy())
                .updatedBy(t.getUpdatedBy())
                .removedBy(t.getRemovedBy())
                .text(t)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sort
    // ──────────────────────────────────────────────────────────────────────────

    private static Comparator<ItemDTO> comparatorFor(String sortBy, String direction) {
        String field = sortBy == null ? "updatedat" : sortBy.trim().toLowerCase(Locale.ROOT);
        boolean isDateField = field.equals("createdat") || field.equals("updatedat");
        boolean desc = direction == null
                ? isDateField               // dates default DESC, everything else ASC
                : direction.trim().equalsIgnoreCase("desc");

        Comparator<ItemDTO> base = switch (field) {
            case "createdat", "created", "added" -> Comparator.comparing(
                    ItemDTO::getCreatedAt, Comparator.nullsLast(Instant::compareTo));
            case "updatedat", "updated", "modified" -> Comparator.comparing(
                    ItemDTO::getUpdatedAt, Comparator.nullsLast(Instant::compareTo));
            case "title", "name", "alpha", "alphabetical" -> Comparator.comparing(
                    ItemDTO::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "code" -> Comparator.comparing(
                    ItemDTO::getCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "projectname", "project" -> Comparator.comparing(
                    ItemDTO::getProjectName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "personname", "person" -> Comparator.comparing(
                    ItemDTO::getPersonName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "type" -> Comparator.comparing(
                    it -> it.getType() == null ? null : it.getType().name(),
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> Comparator.comparing(
                    ItemDTO::getUpdatedAt, Comparator.nullsLast(Instant::compareTo));
        };
        return desc ? base.reversed() : base;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static Set<ItemType> normalizeTypes(List<ItemType> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of(ItemType.AUDIO, ItemType.VIDEO, ItemType.IMAGE, ItemType.TEXT);
        }
        Set<ItemType> out = new HashSet<>(4);
        for (ItemType t : requested) {
            if (t != null) out.add(t);
        }
        return out.isEmpty() ? Set.of(ItemType.AUDIO, ItemType.VIDEO, ItemType.IMAGE, ItemType.TEXT) : out;
    }

    private static Set<String> normalizeLower(List<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>(values.size() * 2);
        for (String v : values) {
            if (v == null) continue;
            String n = v.trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    private static String lower(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
    }

    private static boolean inSet(Set<String> set, String value) {
        return value != null && set.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    /** Treat null personCode (untitled project) as the literal "untitled". */
    private static boolean inSetOrUntitled(Set<String> set, String value) {
        String key = (value == null || value.isBlank()) ? "untitled" : value.trim().toLowerCase(Locale.ROOT);
        return set.contains(key);
    }

    private static boolean anyInSet(Set<String> set, List<String> values) {
        if (values == null || values.isEmpty()) return false;
        for (String v : values) {
            if (v == null) continue;
            if (set.contains(v.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Backend-owned timezone: YYYY-MM-DD range against an Instant column,
     *  resolved to archive-zone (Asia/Baghdad) day bounds via {@link ArchiveTime}. */
    private static boolean withinRange(Instant value, LocalDate from, LocalDate to) {
        Instant fromI = ArchiveTime.startOfDay(from);
        Instant toI = ArchiveTime.endOfDay(to);
        if (fromI == null && toI == null) return true;
        if (value == null) return false;
        if (fromI != null && value.isBefore(fromI)) return false;
        if (toI != null && value.isAfter(toI)) return false;
        return true;
    }

    private static boolean containsAny(String qLower, String... fields) {
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(qLower)) return true;
        }
        return false;
    }

    private static boolean containsAnyList(String qLower, List<String> values) {
        if (values == null) return false;
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(qLower)) return true;
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
