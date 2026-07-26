package ak.dev.khi_archive_platform.platform.service.khilogo;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.dto.khilogo.KhiLogoResponseDTO;
import ak.dev.khi_archive_platform.platform.exceptions.KhiLogoNotFoundException;
import ak.dev.khi_archive_platform.platform.model.khilogo.KhiLogo;
import ak.dev.khi_archive_platform.platform.repo.khilogo.KhiLogoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional
public class KhiLogoService {

    private static final String KHI_LOGO_FOLDER = "khi_logo";

    private final KhiLogoRepository khiLogoRepository;
    private final S3Service s3Service;

    public KhiLogoResponseDTO create(MultipartFile file) {
        requireFile(file);
        String imageUrl = s3Service.upload(file, KHI_LOGO_FOLDER);
        KhiLogo saved = khiLogoRepository.save(KhiLogo.builder().imageUrl(imageUrl).build());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public KhiLogoResponseDTO getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    public KhiLogoResponseDTO update(Long id, MultipartFile file) {
        requireFile(file);
        KhiLogo logo = findOrThrow(id);
        String oldImageUrl = logo.getImageUrl();

        logo.setImageUrl(s3Service.upload(file, KHI_LOGO_FOLDER));
        KhiLogo saved = khiLogoRepository.save(logo);

        if (oldImageUrl != null && s3Service.isOurS3Url(oldImageUrl)) {
            s3Service.deleteFile(oldImageUrl);
        }
        return toResponse(saved);
    }

    public void delete(Long id) {
        KhiLogo logo = findOrThrow(id);
        khiLogoRepository.delete(logo);
        if (logo.getImageUrl() != null && s3Service.isOurS3Url(logo.getImageUrl())) {
            s3Service.deleteFile(logo.getImageUrl());
        }
    }

    private KhiLogo findOrThrow(Long id) {
        return khiLogoRepository.findById(id)
                .orElseThrow(() -> new KhiLogoNotFoundException("Khi logo not found: " + id));
    }

    private void requireFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Logo image file is required.");
        }
    }

    private KhiLogoResponseDTO toResponse(KhiLogo logo) {
        return KhiLogoResponseDTO.builder()
                .id(logo.getId())
                .imageUrl(logo.getImageUrl())
                .createdAt(logo.getCreatedAt())
                .updatedAt(logo.getUpdatedAt())
                .build();
    }
}
