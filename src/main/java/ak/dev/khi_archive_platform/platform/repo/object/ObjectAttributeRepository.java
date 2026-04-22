package ak.dev.khi_archive_platform.platform.repo.object;

import ak.dev.khi_archive_platform.platform.model.object.ObjectAttribute;
import ak.dev.khi_archive_platform.platform.model.category.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObjectAttributeRepository extends JpaRepository<ObjectAttribute, Long> {
    Optional<ObjectAttribute> findByObjectCodeAndDeletedAtIsNull(String objectCode);
    List<ObjectAttribute> findAllByDeletedAtIsNull();
    boolean existsByObjectCodeAndDeletedAtIsNull(String objectCode);
    boolean existsByCategoryAndDeletedAtIsNull(Category category);
    long countByCategory(Category category);
}

