package ak.dev.khi_archive_platform.user.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;

/**
 * One role + the authority strings it grants. Powers admin-console UI that
 * lets the operator see what each role implies before assigning it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleCatalogDTO implements Serializable {
    private String name;
    private Set<String> authorities;
}
