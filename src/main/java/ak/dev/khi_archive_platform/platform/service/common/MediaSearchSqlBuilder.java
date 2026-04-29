package ak.dev.khi_archive_platform.platform.service.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Builds dynamic, multi-token native SQL for media search.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Caller tokenizes the user query (whitespace-split, punctuation-only
 *       tokens dropped, lowercased, deduplicated).</li>
 *   <li>For each token a candidate-IDs CTE is generated: an OR'd set of
 *       prefix-LIKE, substring-LIKE, and trigram-similarity (`%`) probes
 *       against every searchable column on the entity AND every child
 *       collection table. Each CTE is bounded by {@code prefilter} so worst-
 *       case work is fixed regardless of table size.</li>
 *   <li>The token CTEs are inner-joined to the entity table — that's the
 *       AND across tokens. A row only survives if EVERY token matched
 *       SOMEWHERE in its fields/collections.</li>
 *   <li>Ranking is a 3-tier sum across tokens:
 *       <br>tier 1 = #tokens that prefix-match a primary column
 *       <br>tier 2 = #tokens that substring-match a primary column
 *       <br>tier 3 = sum of per-token best similarity over primary cols + child tables</li>
 * </ol>
 *
 * <p>This makes "دیوانی نالی" match "دیوانی نالی — Track 1" cleanly (both
 * tokens found in the title), and "Hejar Track 9" match by combining a hit
 * on poet=Hejar with a hit on the title containing "Track 9".
 */
public final class MediaSearchSqlBuilder {

    private MediaSearchSqlBuilder() {}

    public record ChildTable(String table, String fkColumn, String valueColumn) {}

    /**
     * @param entityTable        physical table (e.g. "images")
     * @param entityIdColumn     PK column (typically "id")
     * @param rankPrimaryCols    columns weighted highest in tier-1/tier-2 ranking
     *                           (titles, codes, key person/place names)
     * @param allTextCols        every column to search via prefix/substring/fuzzy
     * @param children           child collection tables to search and rank
     */
    public record Spec(
            String entityTable,
            String entityIdColumn,
            List<String> rankPrimaryCols,
            List<String> allTextCols,
            List<ChildTable> children
    ) {}

    public record Built(String sql, Map<String, Object> params) {}

    /**
     * Tokenizes {@code query} on whitespace, drops tokens with no letter/digit,
     * deduplicates while preserving order, and trims each token.
     */
    public static List<String> tokenize(String query) {
        if (query == null) return List.of();
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : Arrays.asList(trimmed.split("\\s+"))) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (!t.matches(".*[\\p{L}\\p{N}].*")) continue; // require ≥1 letter/digit
            out.add(t);
        }
        return new ArrayList<>(out);
    }

    /** Escape SQL LIKE wildcards (\, %, _) so they're literal in patterns. */
    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static Built build(Spec spec, List<String> tokens, int prefilter, int limit) {
        if (tokens == null || tokens.isEmpty()) {
            // Empty query → empty result (caller already short-circuits, but safe).
            return new Built("SELECT * FROM " + spec.entityTable() + " WHERE 1=0 LIMIT 0", Map.of());
        }

        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder(8192);
        sql.append("WITH ");

        // --- Per-token candidate CTEs --------------------------------------
        for (int i = 0; i < tokens.size(); i++) {
            String tk = tokens.get(i);
            String escaped = escapeLike(tk);
            params.put("q" + i, escaped);
            params.put("qRaw" + i, tk);

            if (i > 0) sql.append(",\n");
            sql.append("t").append(i).append("_cands AS (\n");

            // Entity-level legs
            sql.append("  SELECT e.").append(spec.entityIdColumn()).append(" AS id\n");
            sql.append("    FROM ").append(spec.entityTable()).append(" e\n");
            sql.append("   WHERE e.removed_at IS NULL\n     AND (\n");
            for (int c = 0; c < spec.allTextCols().size(); c++) {
                String col = spec.allTextCols().get(c);
                if (c > 0) sql.append("       OR ");
                else        sql.append("          ");
                sql.append("LOWER(COALESCE(e.").append(col).append(", '')) LIKE LOWER(:q").append(i).append(") || '%' ESCAPE '\\'\n");
                sql.append("       OR LOWER(COALESCE(e.").append(col).append(", '')) LIKE '%' || LOWER(:q").append(i).append(") || '%' ESCAPE '\\'\n");
                sql.append("       OR LOWER(COALESCE(e.").append(col).append(", '')) % LOWER(:qRaw").append(i).append(")\n");
            }
            sql.append("     )\n");

            // Child-table legs
            for (ChildTable ct : spec.children()) {
                sql.append("  UNION\n");
                sql.append("  SELECT c.").append(ct.fkColumn()).append(" FROM ").append(ct.table()).append(" c\n");
                sql.append("   WHERE LOWER(c.").append(ct.valueColumn()).append(") LIKE LOWER(:q").append(i).append(") || '%' ESCAPE '\\'\n");
                sql.append("      OR LOWER(c.").append(ct.valueColumn()).append(") LIKE '%' || LOWER(:q").append(i).append(") || '%' ESCAPE '\\'\n");
                sql.append("      OR LOWER(c.").append(ct.valueColumn()).append(") % LOWER(:qRaw").append(i).append(")\n");
            }
            sql.append("  LIMIT ").append(prefilter).append("\n");
            sql.append(")");
        }

        // --- Main SELECT: AND across tokens via inner join ----------------
        sql.append("\nSELECT e.* FROM ").append(spec.entityTable()).append(" e\n");
        for (int i = 0; i < tokens.size(); i++) {
            sql.append("  JOIN t").append(i).append("_cands ON t").append(i).append("_cands.id = e.").append(spec.entityIdColumn()).append("\n");
        }
        sql.append(" WHERE e.removed_at IS NULL\n");

        // --- Ranking ------------------------------------------------------
        sql.append(" ORDER BY\n");

        // Tier 1: count of tokens that PREFIX-MATCH at least one primary column
        sql.append("   (");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) sql.append(" + ");
            sql.append("(CASE WHEN ");
            for (int c = 0; c < spec.rankPrimaryCols().size(); c++) {
                if (c > 0) sql.append(" OR ");
                String col = spec.rankPrimaryCols().get(c);
                sql.append("LOWER(COALESCE(e.").append(col).append(", '')) LIKE LOWER(:q").append(i).append(") || '%' ESCAPE '\\'");
            }
            sql.append(" THEN 1 ELSE 0 END)");
        }
        sql.append(") DESC,\n");

        // Tier 2: count of tokens that SUBSTRING-MATCH at least one primary column
        sql.append("   (");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) sql.append(" + ");
            sql.append("(CASE WHEN ");
            for (int c = 0; c < spec.rankPrimaryCols().size(); c++) {
                if (c > 0) sql.append(" OR ");
                String col = spec.rankPrimaryCols().get(c);
                sql.append("LOWER(COALESCE(e.").append(col).append(", '')) LIKE '%' || LOWER(:q").append(i).append(") || '%' ESCAPE '\\'");
            }
            sql.append(" THEN 1 ELSE 0 END)");
        }
        sql.append(") DESC,\n");

        // Tier 3: sum of per-token best similarity (primary cols + child collections)
        sql.append("   (");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) sql.append(" + ");
            sql.append("GREATEST(");
            int parts = 0;
            for (String col : spec.rankPrimaryCols()) {
                if (parts++ > 0) sql.append(", ");
                sql.append("similarity(LOWER(COALESCE(e.").append(col).append(", '')), LOWER(:qRaw").append(i).append("))");
            }
            for (ChildTable ct : spec.children()) {
                if (parts++ > 0) sql.append(", ");
                sql.append("COALESCE((SELECT MAX(similarity(LOWER(c.").append(ct.valueColumn())
                        .append("), LOWER(:qRaw").append(i).append("))) FROM ").append(ct.table())
                        .append(" c WHERE c.").append(ct.fkColumn()).append(" = e.").append(spec.entityIdColumn()).append("), 0)");
            }
            sql.append(")");
        }
        sql.append(") DESC,\n");

        sql.append("   e.").append(spec.entityIdColumn()).append(" ASC\n");
        sql.append(" LIMIT ").append(limit);

        return new Built(sql.toString(), params);
    }
}
