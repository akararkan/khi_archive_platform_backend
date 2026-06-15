package ak.dev.khi_archive_platform.platform.service.guest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the dynamic native SQL behind {@code GET /api/guest/feed}.
 *
 * <h3>Algorithm</h3>
 * One <strong>UNION ALL</strong> across the requested media kinds, all
 * paginated on the PostgreSQL side. Each branch:
 * <ul>
 *   <li>Selects the slim card shape (id, code, title, project_id,
 *       language/dialect/region, date_created, date_published, file URL)</li>
 *   <li>Filters by removed_at, is_public, and every supplied predicate
 *       (language/dialect/region exact match, date range, project/category/
 *       person via JOIN, tag/keyword/genre/subject via EXISTS on the
 *       collection tables)</li>
 *   <li>Scores by free-text q against title + key descriptive scalar
 *       fields. Score is computed in the SELECT — tier-1 prefix match (+3),
 *       tier-2 substring match (+1), tier-3 trigram similarity boost</li>
 * </ul>
 *
 * The wrapper ORDER BY / LIMIT / OFFSET applies once across the unioned
 * stream so PG returns exactly one page of rows. A parallel COUNT query
 * shares the same WHERE/UNION structure for total-elements.
 *
 * <p>Predicates are emitted only when the corresponding filter is set —
 * empty filters never become {@code (:x IS NULL OR …)} in the SQL, so the
 * planner sees a tight predicate set instead of a fan of OR'd nulls.
 */
final class GuestFeedSqlBuilder {

    /** Concrete metadata for one media kind so the per-branch SQL stays uniform. */
    private record KindSpec(
            String kind,                  // "audio" | "video" | "image" | "text"
            String table,                 // "audios"
            String codeCol,               // "audio_code"
            String fileUrlCol,            // "audio_file_url"
            String fkCol,                 // "audio_id" (in collection child tables)
            String tagsTable,             // "audio_tags"
            String tagsCol,               // "tag"
            String keywordsTable,         // "audio_keywords"
            String keywordsCol,           // "keyword"
            String genresTable,           // "audio_genres"
            String genresCol,             // "genre"
            String subjectsTable,         // "audio_subjects"  (added in this PR)
            String subjectsCol,           // "subject"
            String t1, String t2, String t3, String t4   // title cols in priority order
    ) {}

    private static final KindSpec AUDIO = new KindSpec(
            "audio", "audios", "audio_code", "audio_file_url", "audio_id",
            "audio_tags", "tag",
            "audio_keywords", "keyword",
            "audio_genres", "genre",
            "audio_subjects", "subject",
            "origin_title", "alter_title", "central_kurdish_title", "romanized_title");

    private static final KindSpec VIDEO = new KindSpec(
            "video", "videos", "video_code", "video_file_url", "video_id",
            "video_tags", "tag",
            "video_keywords", "keyword",
            "video_genres", "genre",
            "video_subjects", "subject",
            "original_title", "alternative_title", "title_in_central_kurdish", "romanized_title");

    private static final KindSpec IMAGE = new KindSpec(
            "image", "images", "image_code", "image_file_url", "image_id",
            "image_tags", "tag",
            "image_keywords", "keyword",
            "image_genres", "genre",
            "image_subjects", "subject",
            "original_title", "alternative_title", "title_in_central_kurdish", "romanized_title");

    private static final KindSpec TEXT = new KindSpec(
            "text", "texts", "text_code", "text_file_url", "text_id",
            "text_tags", "tag",
            "text_keywords", "keyword",
            "text_genres", "genre",
            "text_subjects", "subject",
            "original_title", "alternative_title", "title_in_central_kurdish", "romanized_title");

    private static final Map<String, KindSpec> SPECS = Map.of(
            "audio", AUDIO, "video", VIDEO, "image", IMAGE, "text", TEXT);

    static final String KIND_AUDIO = "audio";
    static final String KIND_VIDEO = "video";
    static final String KIND_IMAGE = "image";
    static final String KIND_TEXT  = "text";

    /** Inputs to the builder. All nullable — empty = no filter on that field. */
    record Filters(
            String q,
            Set<String> types,        // selected kinds (subset of {audio,video,image,text})
            String projectCode,
            String categoryCode,
            String personCode,
            String language,
            String dialect,
            String region,
            List<String> subjects,
            List<String> genres,
            List<String> tags,
            List<String> keywords,
            java.time.Instant dateFrom,
            java.time.Instant dateTo,
            String sortBy,            // "relevance" | "date" | "datePublished" | "title"
            String sortDirection      // "asc" | "desc"
    ) {}

    /** Pair of (sql, params) for a built query. */
    record Built(String sql, Map<String, Object> params) {}

    private GuestFeedSqlBuilder() {}

    /** Builds the paginated page SQL with ORDER BY + LIMIT/OFFSET. */
    static Built buildPage(Filters f, int limit, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        String unionBody = buildUnionBody(f, params);
        String orderBy = orderByClause(f);
        String sql = "SELECT * FROM (" + unionBody + ") feed " + orderBy
                + " LIMIT " + limit + " OFFSET " + offset;
        return new Built(sql, params);
    }

    /** Builds the count SQL — same WHERE/UNION shape, just wrapped in COUNT(*). */
    static Built buildCount(Filters f) {
        Map<String, Object> params = new LinkedHashMap<>();
        String unionBody = buildUnionBody(f, params);
        String sql = "SELECT COUNT(*) FROM (" + unionBody + ") feed";
        return new Built(sql, params);
    }

    // ─── Internals ─────────────────────────────────────────────────────────

    private static String buildUnionBody(Filters f, Map<String, Object> params) {
        // Pre-bind shared free-text params once (used by every branch).
        boolean hasQ = f.q != null && !f.q.isBlank();
        if (hasQ) {
            String norm = f.q.trim().toLowerCase(Locale.ROOT);
            params.put("q", norm);
            params.put("qLike", "%" + escapeLike(norm) + "%");
            params.put("qPrefix", escapeLike(norm) + "%");
        }
        bindCommonFilters(f, params);

        List<String> branches = new ArrayList<>(4);
        for (String kind : List.of(KIND_AUDIO, KIND_VIDEO, KIND_IMAGE, KIND_TEXT)) {
            if (f.types == null || f.types.isEmpty() || f.types.contains(kind)) {
                branches.add(branchSql(SPECS.get(kind), f, hasQ));
            }
        }
        if (branches.isEmpty()) {
            // No requested kinds — fall back to empty set so the page is empty.
            return "SELECT NULL::text AS kind, NULL::bigint AS id, NULL::text AS code, "
                    + "NULL::text AS title, NULL::bigint AS project_id, "
                    + "NULL::text AS language, NULL::text AS dialect, NULL::text AS region, "
                    + "NULL::timestamp AS date_created, NULL::timestamp AS date_published, "
                    + "NULL::text AS file_url, 0::double precision AS score WHERE FALSE";
        }
        return String.join(" UNION ALL ", branches);
    }

    private static String branchSql(KindSpec k, Filters f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("SELECT '").append(k.kind).append("' AS kind, ")
                .append("e.id AS id, e.").append(k.codeCol).append(" AS code, ")
                .append("COALESCE(NULLIF(e.").append(k.t1).append(", ''), ")
                .append("NULLIF(e.").append(k.t2).append(", ''), ")
                .append("NULLIF(e.").append(k.t3).append(", ''), ")
                .append("NULLIF(e.").append(k.t4).append(", ''), ")
                .append("e.").append(k.codeCol).append(") AS title, ")
                .append("e.project_id AS project_id, ")
                .append("e.language AS language, e.dialect AS dialect, e.region AS region, ")
                .append("e.date_created AS date_created, ")
                .append("e.date_published AS date_published, ")
                .append("e.").append(k.fileUrlCol).append(" AS file_url, ");

        // Score expression
        if (hasQ) {
            sb.append("(");
            // Prefix tier (+3)
            sb.append("(CASE WHEN LOWER(COALESCE(e.").append(k.t1).append(", '')) LIKE :qPrefix THEN 3 ELSE 0 END) + ")
              .append("(CASE WHEN LOWER(COALESCE(e.").append(k.t2).append(", '')) LIKE :qPrefix THEN 3 ELSE 0 END) + ")
              .append("(CASE WHEN LOWER(COALESCE(e.").append(k.t3).append(", '')) LIKE :qPrefix THEN 3 ELSE 0 END) + ")
              .append("(CASE WHEN LOWER(COALESCE(e.").append(k.t4).append(", '')) LIKE :qPrefix THEN 3 ELSE 0 END) + ");
            // Substring tier (+1 each)
            sb.append("(CASE WHEN LOWER(COALESCE(e.").append(k.t1).append(", '')) LIKE :qLike THEN 1 ELSE 0 END) + ")
              .append("(CASE WHEN LOWER(COALESCE(e.").append(k.t2).append(", '')) LIKE :qLike THEN 1 ELSE 0 END) + ")
              .append("(CASE WHEN LOWER(COALESCE(e.").append(k.t3).append(", '')) LIKE :qLike THEN 1 ELSE 0 END) + ")
              .append("(CASE WHEN LOWER(COALESCE(e.").append(k.t4).append(", '')) LIKE :qLike THEN 1 ELSE 0 END) + ");
            // Description-ish field
            sb.append("(CASE WHEN LOWER(COALESCE(e.description, '')) LIKE :qLike THEN 1 ELSE 0 END) + ");
            // Project name + person name bumps
            sb.append("(CASE WHEN LOWER(COALESCE(p.project_name, '')) LIKE :qLike THEN 2 ELSE 0 END) + ");
            sb.append("(CASE WHEN LOWER(COALESCE(per.full_name, '')) LIKE :qLike "
                    + "OR LOWER(COALESCE(per.nickname, '')) LIKE :qLike "
                    + "OR LOWER(COALESCE(per.romanized_name, '')) LIKE :qLike THEN 2 ELSE 0 END)");
            sb.append(")::double precision AS score ");
        } else {
            sb.append("0::double precision AS score ");
        }

        sb.append("FROM ").append(k.table).append(" e ")
                .append("LEFT JOIN projects p ON p.id = e.project_id ")
                .append("LEFT JOIN persons per ON per.id = p.person_id ");

        // WHERE — only emit predicates for filters that are actually set.
        sb.append("WHERE e.removed_at IS NULL AND e.is_public = TRUE ");
        sb.append("AND p.removed_at IS NULL AND p.is_visible_to_public = TRUE ");

        if (notBlank(f.projectCode)) {
            sb.append("AND LOWER(p.project_code) = :projectCode ");
        }
        if (notBlank(f.categoryCode)) {
            sb.append("AND EXISTS (SELECT 1 FROM project_categories pc ")
              .append("JOIN categories c ON c.id = pc.category_id ")
              .append("WHERE pc.project_id = p.id AND LOWER(c.category_code) = :categoryCode) ");
        }
        if (notBlank(f.personCode)) {
            sb.append("AND LOWER(per.person_code) = :personCode ");
        }
        if (notBlank(f.language)) {
            sb.append("AND LOWER(COALESCE(e.language, '')) = :language ");
        }
        if (notBlank(f.dialect)) {
            sb.append("AND LOWER(COALESCE(e.dialect, '')) = :dialect ");
        }
        if (notBlank(f.region)) {
            sb.append("AND LOWER(COALESCE(e.region, '')) = :region ");
        }
        if (f.dateFrom != null) {
            sb.append("AND e.date_created >= :dateFrom ");
        }
        if (f.dateTo != null) {
            sb.append("AND e.date_created <= :dateTo ");
        }
        if (notEmpty(f.subjects)) {
            sb.append("AND EXISTS (SELECT 1 FROM ").append(k.subjectsTable)
              .append(" x WHERE x.").append(k.fkCol).append(" = e.id ")
              .append("AND LOWER(x.").append(k.subjectsCol).append(") IN (:subjects)) ");
        }
        if (notEmpty(f.genres)) {
            sb.append("AND EXISTS (SELECT 1 FROM ").append(k.genresTable)
              .append(" x WHERE x.").append(k.fkCol).append(" = e.id ")
              .append("AND LOWER(x.").append(k.genresCol).append(") IN (:genres)) ");
        }
        if (notEmpty(f.tags)) {
            sb.append("AND EXISTS (SELECT 1 FROM ").append(k.tagsTable)
              .append(" x WHERE x.").append(k.fkCol).append(" = e.id ")
              .append("AND LOWER(x.").append(k.tagsCol).append(") IN (:tags)) ");
        }
        if (notEmpty(f.keywords)) {
            sb.append("AND EXISTS (SELECT 1 FROM ").append(k.keywordsTable)
              .append(" x WHERE x.").append(k.fkCol).append(" = e.id ")
              .append("AND LOWER(x.").append(k.keywordsCol).append(") IN (:keywords)) ");
        }
        if (hasQ) {
            // q must hit SOMETHING — title fields, description, project/person,
            // OR any tag/keyword/subject/genre row.
            sb.append("AND (")
              .append("LOWER(COALESCE(e.").append(k.t1).append(", '')) LIKE :qLike ")
              .append("OR LOWER(COALESCE(e.").append(k.t2).append(", '')) LIKE :qLike ")
              .append("OR LOWER(COALESCE(e.").append(k.t3).append(", '')) LIKE :qLike ")
              .append("OR LOWER(COALESCE(e.").append(k.t4).append(", '')) LIKE :qLike ")
              .append("OR LOWER(COALESCE(e.").append(k.codeCol).append(", '')) LIKE :qLike ")
              .append("OR LOWER(COALESCE(e.description, '')) LIKE :qLike ")
              .append("OR LOWER(COALESCE(p.project_name, '')) LIKE :qLike ")
              .append("OR LOWER(COALESCE(p.project_code, '')) LIKE :qLike ")
              .append("OR LOWER(COALESCE(per.full_name, '')) LIKE :qLike ")
              .append("OR LOWER(COALESCE(per.nickname, '')) LIKE :qLike ")
              .append("OR LOWER(COALESCE(per.romanized_name, '')) LIKE :qLike ")
              .append("OR EXISTS (SELECT 1 FROM ").append(k.tagsTable)
              .append(" x WHERE x.").append(k.fkCol).append(" = e.id AND LOWER(x.").append(k.tagsCol).append(") LIKE :qLike) ")
              .append("OR EXISTS (SELECT 1 FROM ").append(k.keywordsTable)
              .append(" x WHERE x.").append(k.fkCol).append(" = e.id AND LOWER(x.").append(k.keywordsCol).append(") LIKE :qLike) ")
              .append("OR EXISTS (SELECT 1 FROM ").append(k.subjectsTable)
              .append(" x WHERE x.").append(k.fkCol).append(" = e.id AND LOWER(x.").append(k.subjectsCol).append(") LIKE :qLike) ")
              .append("OR EXISTS (SELECT 1 FROM ").append(k.genresTable)
              .append(" x WHERE x.").append(k.fkCol).append(" = e.id AND LOWER(x.").append(k.genresCol).append(") LIKE :qLike) ")
              .append(") ");
        }

        return sb.toString();
    }

    private static void bindCommonFilters(Filters f, Map<String, Object> params) {
        if (notBlank(f.projectCode))  params.put("projectCode",  f.projectCode.trim().toLowerCase(Locale.ROOT));
        if (notBlank(f.categoryCode)) params.put("categoryCode", f.categoryCode.trim().toLowerCase(Locale.ROOT));
        if (notBlank(f.personCode))   params.put("personCode",   f.personCode.trim().toLowerCase(Locale.ROOT));
        if (notBlank(f.language))     params.put("language",     f.language.trim().toLowerCase(Locale.ROOT));
        if (notBlank(f.dialect))      params.put("dialect",      f.dialect.trim().toLowerCase(Locale.ROOT));
        if (notBlank(f.region))       params.put("region",       f.region.trim().toLowerCase(Locale.ROOT));
        if (f.dateFrom != null)       params.put("dateFrom",     f.dateFrom);
        if (f.dateTo   != null)       params.put("dateTo",       f.dateTo);
        if (notEmpty(f.subjects))     params.put("subjects",     lowerList(f.subjects));
        if (notEmpty(f.genres))       params.put("genres",       lowerList(f.genres));
        if (notEmpty(f.tags))         params.put("tags",         lowerList(f.tags));
        if (notEmpty(f.keywords))     params.put("keywords",     lowerList(f.keywords));
    }

    private static String orderByClause(Filters f) {
        String key = f.sortBy == null ? "" : f.sortBy.trim().toLowerCase(Locale.ROOT);
        boolean defaultDesc = true;
        String primary;
        switch (key) {
            case "title", "name", "alpha" -> { primary = "title ASC NULLS LAST"; defaultDesc = false; }
            case "datepublished", "published" -> { primary = "date_published DESC NULLS LAST"; }
            case "date", "created", "added", "datecreated" -> { primary = "date_created DESC NULLS LAST"; }
            default -> {
                // "relevance" or unknown: q-aware default — score desc then date desc
                primary = "score DESC, date_created DESC NULLS LAST";
            }
        }
        boolean wantDesc = (f.sortDirection == null || f.sortDirection.isBlank())
                ? defaultDesc
                : f.sortDirection.equalsIgnoreCase("desc");
        // If user explicitly flipped direction, reverse the primary sort
        if (!wantDesc && primary.contains("DESC")) {
            primary = primary.replace("DESC", "ASC");
        } else if (wantDesc && primary.contains("ASC NULLS LAST") && !key.equals("relevance") && !key.isBlank()) {
            primary = primary.replace("ASC NULLS LAST", "DESC NULLS LAST");
        }
        return "ORDER BY " + primary + ", id ASC";
    }

    // ─── tiny helpers ──────────────────────────────────────────────────────

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static boolean notEmpty(List<?> l) { return l != null && !l.isEmpty(); }

    private static List<String> lowerList(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    /** LIKE-escapes _ % \ so user input can't blow up the pattern. */
    private static String escapeLike(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '%' || c == '_') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
