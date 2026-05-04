package ak.dev.khi_archive_platform.platform.dto.text;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.util.List;

/**
 * Filter + sort parameters for the text listing.
 *
 * Sort:
 *   sortBy        — textCode | originalTitle (alpha) | author (alpha) | language (alpha)
 *                   | createdAt | updatedAt
 *                   | dateCreated | printDate | dateModified | datePublished | dateCopyrighted
 *                   | versionNumber | copyNumber | pageCount
 *                   (synonyms accepted: name/alpha, added/dateAdded, modified/dateModified, pages)
 *   sortDirection — asc | desc (default asc)
 *
 * Categorical equals (case-insensitive exact match):
 *   documentType, script, edition, volume, series,
 *   textVersion, textStatus, audience,
 *   extension, orientation, size, physicalDimensions,
 *   language, dialect, printingHouse,
 *   accrualMethod, lccClassification, availability, licenseType,
 *   isbn, assignmentNumber.
 *
 * Long-text contains (case-insensitive substring):
 *   description, transcription, author, contributors,
 *   provenance, archiveCataloging,
 *   physicalLabel, locationInArchiveRoom, note,
 *   copyright, rightOwner, usageRights, owner, publisher.
 *
 * Collection any/all (case-insensitive):
 *   subject + subjectMatch, genre + genreMatch,
 *   tags + tagMatch, keywords + keywordMatch.
 *
 * Boolean: physicalAvailability.
 *
 * Numeric ranges (Integer min/max, inclusive):
 *   versionNumberMin/Max, copyNumberMin/Max, pageCountMin/Max.
 *
 * Date ranges (ISO-8601, inclusive):
 *   dateCreatedFrom/To, printDateFrom/To,
 *   dateModifiedFrom/To, datePublishedFrom/To, dateCopyrightedFrom/To,
 *   createdFrom/To (audit), updatedFrom/To (audit).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TextFilterParams {

    private String sortBy;
    private String sortDirection;

    // ─── Categorical equals (case-insensitive exact) ─────────────────────────────
    private String documentType;
    private String script;
    private String edition;
    private String volume;
    private String series;
    private String textVersion;
    private String textStatus;
    private String audience;
    private String extension;
    private String orientation;
    private String size;
    private String physicalDimensions;
    private String language;
    private String dialect;
    private String printingHouse;
    private String accrualMethod;
    private String lccClassification;
    private String availability;
    private String licenseType;
    private String isbn;
    private String assignmentNumber;

    // ─── Long-text contains (case-insensitive substring) ─────────────────────────
    private String description;
    private String transcription;
    private String author;
    private String contributors;
    private String provenance;
    private String archiveCataloging;
    private String physicalLabel;
    private String locationInArchiveRoom;
    private String note;
    private String copyright;
    private String rightOwner;
    private String usageRights;
    private String owner;
    private String publisher;

    // ─── Collections (any|all) ───────────────────────────────────────────────────
    private List<String> subject;
    private String subjectMatch;
    private List<String> genre;
    private String genreMatch;
    private List<String> tags;
    private String tagMatch;
    private List<String> keywords;
    private String keywordMatch;

    // ─── Boolean ─────────────────────────────────────────────────────────────────
    private Boolean physicalAvailability;

    // ─── Numeric ranges ──────────────────────────────────────────────────────────
    private Integer versionNumberMin;
    private Integer versionNumberMax;
    private Integer copyNumberMin;
    private Integer copyNumberMax;
    private Integer pageCountMin;
    private Integer pageCountMax;

    // ─── Date ranges (ISO-8601 instants) ─────────────────────────────────────────
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateCreatedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateCreatedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant printDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant printDateTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateModifiedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateModifiedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant datePublishedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant datePublishedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateCopyrightedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateCopyrightedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant createdFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant createdTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant updatedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant updatedTo;

    public boolean isEmpty() {
        return blank(sortBy) && blank(sortDirection)
                && blank(documentType) && blank(script)
                && blank(edition) && blank(volume) && blank(series)
                && blank(textVersion) && blank(textStatus) && blank(audience)
                && blank(extension) && blank(orientation) && blank(size) && blank(physicalDimensions)
                && blank(language) && blank(dialect) && blank(printingHouse)
                && blank(accrualMethod) && blank(lccClassification)
                && blank(availability) && blank(licenseType)
                && blank(isbn) && blank(assignmentNumber)
                && blank(description) && blank(transcription)
                && blank(author) && blank(contributors)
                && blank(provenance) && blank(archiveCataloging)
                && blank(physicalLabel) && blank(locationInArchiveRoom) && blank(note)
                && blank(copyright) && blank(rightOwner) && blank(usageRights)
                && blank(owner) && blank(publisher)
                && empty(subject) && empty(genre) && empty(tags) && empty(keywords)
                && physicalAvailability == null
                && versionNumberMin == null && versionNumberMax == null
                && copyNumberMin == null && copyNumberMax == null
                && pageCountMin == null && pageCountMax == null
                && dateCreatedFrom == null && dateCreatedTo == null
                && printDateFrom == null && printDateTo == null
                && dateModifiedFrom == null && dateModifiedTo == null
                && datePublishedFrom == null && datePublishedTo == null
                && dateCopyrightedFrom == null && dateCopyrightedTo == null
                && createdFrom == null && createdTo == null
                && updatedFrom == null && updatedTo == null;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean empty(List<?> l) { return l == null || l.isEmpty(); }
}
