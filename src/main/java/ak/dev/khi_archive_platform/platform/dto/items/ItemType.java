package ak.dev.khi_archive_platform.platform.dto.items;

/**
 * Discriminator for a row in the unified Items view. Maps 1:1 to the four
 * media tables (audios, videos, images, texts). Used by {@link ItemDTO#type}
 * so the frontend can render each row with the correct card and so the API
 * can filter by a single type or any subset.
 */
public enum ItemType {
    AUDIO,
    VIDEO,
    IMAGE,
    TEXT
}
