package ak.dev.khi_archive_platform.platform.repo.category;

import ak.dev.khi_archive_platform.platform.model.category.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByCategoryCodeAndRemovedAtIsNull(String categoryCode);

    List<Category> findAllByRemovedAtIsNull();

    boolean existsByCategoryCodeAndRemovedAtIsNull(String categoryCode);
}
