package ak.dev.khi_archive_platform.platform.dto.image;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/**
 * Filter + sort parameters for the image listing.
 *
 * Sort:
 *   sortBy        — imageCode | originalTitle (alpha) | createdAt | updatedAt
 *                   | dateCreated | dateModified | datePublished | dateCopyrighted
 *                   | versionNumber | copyNumber
 *                   (synonyms accepted: name/alpha, added/dateAdded, modified/dateModified)
 *   sortDirection — asc | desc (default asc)
 *
 * Categorical equals (case-insensitive exact match):
 *   form, imageStatus, imageVersion, audience,
 *   extension, orientation, dimension, bitDepth, dpi,
 *   manufacturer, model, lens,
 *   accrualMethod, lccClassification,
 *   availability, licenseType.
 *
 * Long-text contains (case-insensitive substring):
 *   event, location, description,
 *   personShownInImage, creatorArtistPhotographer, contributor,
 *   provenance, photostory, archiveCataloging,
 *   physicalLabel, locationInArchiveRoom, note,
 *   copyright, rightOwner, usageRights, owner, publisher.
 *
 * Collection any/all (case-insensitive):
 *   subject + subjectMatch, genre + genreMatch,
 *   colorOfImage + colorMatch, whereThisImageUsed + usageMatch,
 *   tags + tagMatch, keywords + keywordMatch.
 *
 * Boolean: physicalAvailability.
 *
 * Numeric ranges (Integer min/max, inclusive):
 *   versionNumberMin/Max, copyNumberMin/Max.
 *
 * Date ranges (ISO-8601, inclusive):
 *   dateCreatedFrom/To, dateModifiedFrom/To, datePublishedFrom/To,
 *   dateCopyrightedFrom/To, createdFrom/To (audit), updatedFrom/To (audit).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageFilterParams {

    private String sortBy;
    private String sortDirection;

    // ─── Categorical equals (case-insensitive exact) ─────────────────────────────
    private String form;
    private String imageStatus;
    private String imageVersion;
    private String audience;
    private String extension;
    private String orientation;
    private String dimension;
    private String bitDepth;
    private String dpi;
    private String manufacturer;
    private String model;
    private String lens;
    private String accrualMethod;
    private String lccClassification;
    private String availability;
    private String licenseType;

    // ─── Long-text contains (case-insensitive substring) ─────────────────────────
    private String event;
    private String location;
    private String description;
    private String personShownInImage;
    private String creatorArtistPhotographer;
    private String contributor;
    private String provenance;
    private String photostory;
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
    private List<String> colorOfImage;
    private String colorMatch;
    private List<String> whereThisImageUsed;
    private String usageMatch;
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

    // ─── Date ranges (ISO-8601 instants) ─────────────────────────────────────────
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate dateCreatedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate dateCreatedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate dateModifiedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate dateModifiedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate datePublishedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate datePublishedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate dateCopyrightedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate dateCopyrightedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate createdFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate createdTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate updatedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate updatedTo;

    public boolean isEmpty() {
        return blank(sortBy) && blank(sortDirection)
                && blank(form) && blank(imageStatus) && blank(imageVersion) && blank(audience)
                && blank(extension) && blank(orientation) && blank(dimension)
                && blank(bitDepth) && blank(dpi)
                && blank(manufacturer) && blank(model) && blank(lens)
                && blank(accrualMethod) && blank(lccClassification)
                && blank(availability) && blank(licenseType)
                && blank(event) && blank(location) && blank(description)
                && blank(personShownInImage) && blank(creatorArtistPhotographer) && blank(contributor)
                && blank(provenance) && blank(photostory) && blank(archiveCataloging)
                && blank(physicalLabel) && blank(locationInArchiveRoom) && blank(note)
                && blank(copyright) && blank(rightOwner) && blank(usageRights)
                && blank(owner) && blank(publisher)
                && empty(subject) && empty(genre) && empty(colorOfImage)
                && empty(whereThisImageUsed) && empty(tags) && empty(keywords)
                && physicalAvailability == null
                && versionNumberMin == null && versionNumberMax == null
                && copyNumberMin == null && copyNumberMax == null
                && dateCreatedFrom == null && dateCreatedTo == null
                && dateModifiedFrom == null && dateModifiedTo == null
                && datePublishedFrom == null && datePublishedTo == null
                && dateCopyrightedFrom == null && dateCopyrightedTo == null
                && createdFrom == null && createdTo == null
                && updatedFrom == null && updatedTo == null;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean empty(List<?> l) { return l == null || l.isEmpty(); }
}
