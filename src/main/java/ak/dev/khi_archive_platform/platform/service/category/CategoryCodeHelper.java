package ak.dev.khi_archive_platform.platform.service.category;

import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;

import java.util.regex.Pattern;

public final class CategoryCodeHelper {
    private static final Pattern PATTERN = Pattern.compile(ValidationPatterns.CATEGORY_CODE);

    private CategoryCodeHelper() {
    }

    public static String normalize(String categoryCode) {
        if (categoryCode == null) {
            throw new IllegalArgumentException("Category code is required");
        }
        String trimmed = categoryCode.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Category code is required");
        }
        return trimmed;
    }

    public static String normalizeAndValidate(String categoryCode) {
        String normalized = normalize(categoryCode);
        if (!PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Category code must contain only letters, numbers, underscores, or hyphens");
        }
        return normalized;
    }
}

