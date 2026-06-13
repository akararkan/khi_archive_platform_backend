package ak.dev.khi_archive_platform.platform.service.items;

import ak.dev.khi_archive_platform.platform.dto.items.ItemType;
import ak.dev.khi_archive_platform.platform.exceptions.AudioValidationException;
import ak.dev.khi_archive_platform.platform.service.audio.AudioService;
import ak.dev.khi_archive_platform.platform.service.image.ImageService;
import ak.dev.khi_archive_platform.platform.service.text.TextService;
import ak.dev.khi_archive_platform.platform.service.video.VideoService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Dispatcher for the unified {@code PATCH /api/items/{type}/{code}/visibility}
 * endpoint. Parses the type path-param, performs the per-type
 * {@code {resource}:update} authority check (it's data-dependent, so it
 * can't be expressed as a declarative {@code @PreAuthorize}), then delegates
 * to the matching media service.
 */
@Service
@RequiredArgsConstructor
public class ItemVisibilityService {

    private final AudioService audioService;
    private final VideoService videoService;
    private final ImageService imageService;
    private final TextService textService;

    public Object setVisibility(String rawType,
                                String code,
                                boolean isPublic,
                                Authentication authentication,
                                HttpServletRequest request) {
        ItemType type = parseType(rawType);
        String required = type.name().toLowerCase(Locale.ROOT) + ":update";
        requireAuthority(authentication, required);

        return switch (type) {
            case AUDIO -> audioService.setVisibility(code, isPublic, authentication, request);
            case VIDEO -> videoService.setVisibility(code, isPublic, authentication, request);
            case IMAGE -> imageService.setVisibility(code, isPublic, authentication, request);
            case TEXT  -> textService.setVisibility(code, isPublic, authentication, request);
        };
    }

    private static ItemType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AudioValidationException("Unknown item type: " + raw);
        }
        try {
            return ItemType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AudioValidationException("Unknown item type: " + raw);
        }
    }

    private static void requireAuthority(Authentication authentication, String authority) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required for this operation");
        }
        boolean hasAuthority = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
        if (!hasAuthority) {
            throw new AccessDeniedException(
                    "You don't have permission to perform this action. Required authority: '" + authority + "'.");
        }
    }
}
