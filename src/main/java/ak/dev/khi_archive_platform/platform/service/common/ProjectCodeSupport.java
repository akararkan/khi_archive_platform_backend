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
 *   <li>Untitled project code: supplied by frontend, e.g. DENG-PROJ-000004</li>
 *   <li>Untitled media prefix: code prefix before PROJ, e.g. DENG</li>
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
        if (project != null && project.getProjectCode() != null) {
            String code = project.getProjectCode().trim().toUpperCase(Locale.ROOT);
            String[] markers = {"-PROJ-", "_PROJ_"};
            for (String marker : markers) {
                int idx = code.indexOf(marker);
                if (idx > 0) {
                    return code.substring(0, idx);
                }
            }
        }
        return normalizeProjectNamePrefix(project != null ? project.getProjectName() : null);
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
