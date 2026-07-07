package ak.dev.khi_archive_platform.platform.service.common;

import ak.dev.khi_archive_platform.platform.model.person.Person;
import ak.dev.khi_archive_platform.platform.model.project.Project;

import java.util.Locale;

/**
 * Shared helpers for project and media business codes.
 *
 * <p>Rules:
 * <ul>
 *   <li>Personal project prefix: PERSONCODE</li>
 *   <li>Untitled project prefix: normalized project name, e.g. NATURE</li>
 *   <li>Untitled media prefix: PROJECTNAME(CATEGORYCODE), e.g. NATURE(OLD_PICTURES)</li>
 * </ul>
 */
public final class ProjectCodeSupport {

    private ProjectCodeSupport() {
    }

    public static String projectPrefix(Person person, String projectName) {
        if (person != null) {
            return person.getPersonCode().toUpperCase(Locale.ROOT);
        }
        return normalizeProjectNamePrefix(projectName);
    }

    public static String untitledMediaPrefix(Project project) {
        return normalizeProjectNamePrefix(project.getProjectName()) + "(" + primaryCategoryCode(project) + ")";
    }

    public static String primaryCategoryCode(Project project) {
        if (project == null || project.getCategories() == null || project.getCategories().isEmpty()) {
            throw new IllegalArgumentException("At least one category is required");
        }
        return project.getCategories().get(0).getCategoryCode().toUpperCase(Locale.ROOT);
    }

    private static String normalizeProjectNamePrefix(String projectName) {
        if (projectName == null) {
            throw new IllegalArgumentException("Project name is required for untitled project codes");
        }

        String normalized = projectName.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");

        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Project name is required for untitled project codes");
        }

        return normalized;
    }
}
