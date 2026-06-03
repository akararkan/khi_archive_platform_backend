package ak.dev.khi_archive_platform.platform.repo.physicalmedia;

import ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMediaType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PhysicalMediaTypeRepository extends JpaRepository<PhysicalMediaType, Long> {

    Optional<PhysicalMediaType> findByName(String name);

    boolean existsByName(String name);

    default List<PhysicalMediaType> findAllOrderedByName() {
        return findAll(Sort.by(Sort.Direction.ASC, "name"));
    }
}
