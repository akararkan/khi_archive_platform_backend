package ak.dev.khi_archive_platform.platform.repo.correction;

import ak.dev.khi_archive_platform.platform.enums.CorrectionMediaType;
import ak.dev.khi_archive_platform.platform.enums.CorrectionStatus;
import ak.dev.khi_archive_platform.platform.model.correction.GuestCorrection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface GuestCorrectionRepository
        extends JpaRepository<GuestCorrection, Long>, JpaSpecificationExecutor<GuestCorrection> {

    Optional<GuestCorrection> findByIdAndRemovedAtIsNull(Long id);

    Page<GuestCorrection> findAllByGuestUserIdAndRemovedAtIsNull(Long guestUserId, Pageable pageable);

    long countByStatusAndRemovedAtIsNull(CorrectionStatus status);

    long countByMediaTypeAndRemovedAtIsNull(CorrectionMediaType mediaType);

    long countByMediaTypeAndMediaCodeAndRemovedAtIsNull(CorrectionMediaType mediaType, String mediaCode);
}
