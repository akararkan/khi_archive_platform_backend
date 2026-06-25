package ak.dev.khi_archive_platform.platform.service.project;

import ak.dev.khi_archive_platform.platform.dto.project.ProjectResponseDTO;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ProjectReadCacheTest {

    @Test
    void toResponseKeepsHiddenProjectVisibility() {
        Project project = Project.builder()
                .id(1L)
                .projectCode("TEST_PROJ_000001")
                .projectName("Hidden Test Project")
                .isVisibleToPublic(false)
                .build();

        ProjectResponseDTO response = ProjectReadCache.toResponse(project);

        assertFalse(response.getIsVisibleToPublic());
    }
}
