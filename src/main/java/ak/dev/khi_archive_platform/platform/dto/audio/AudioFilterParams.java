package ak.dev.khi_archive_platform.platform.dto.audio;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.util.List;

/**
 * Filter + sort parameters for the audio listing.
 *
 * Sort:
 *   sortBy        — audioCode | originTitle (alpha) | createdAt | updatedAt
 *                   | dateCreated | datePublished | dateModified | dateCopyrighted
 *                   | audioQualityOutOf10 | versionNumber | copyNumber
 *                   (synonyms accepted: name/alpha, added/dateAdded, modified/dateModified)
 *   sortDirection — asc | desc (default asc)
 *
 * Categorical equals (case-insensitive exact match):
 *   form, typeOfBasta, typeOfMaqam, language, dialect,
 *   typeOfComposition, typeOfPerformance, city, region, audience,
 *   audioChannel, fileExtension, bitRate, bitDepth, sampleRate,
 *   lccClassification, accrualMethod, availability, licenseType.
 *
 * Long-text contains (case-insensitive substring):
 *   speaker, producer, composer, poet, lyrics, recordingVenue,
 *   locationArchive, degitizedBy, degitizationEquipment,
 *   provenance, copyright, rightOwner, usageRights, owner, publisher.
 *
 * Collection any/all (case-insensitive):
 *   genre + genreMatch, contributors + contributorMatch,
 *   tags + tagMatch, keywords + keywordMatch.
 *
 * Boolean: physicalAvailability.
 *
 * Numeric ranges (Integer min/max, inclusive):
 *   audioQualityMin/Max, versionNumberMin/Max, copyNumberMin/Max.
 *
 * Date ranges (ISO-8601, inclusive):
 *   dateCreatedFrom/To, datePublishedFrom/To, dateModifiedFrom/To,
 *   dateCopyrightedFrom/To, createdFrom/To (audit), updatedFrom/To (audit).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioFilterParams {

    private String sortBy;
    private String sortDirection;

    // ─── Categorical equals (case-insensitive exact) ─────────────────────────────
    private String form;
    private String typeOfBasta;
    private String typeOfMaqam;
    private String language;
    private String dialect;
    private String typeOfComposition;
    private String typeOfPerformance;
    private String city;
    private String region;
    private String audience;
    private String audioChannel;
    private String fileExtension;
    private String bitRate;
    private String bitDepth;
    private String sampleRate;
    private String lccClassification;
    private String accrualMethod;
    private String availability;
    private String licenseType;

    // ─── Long-text contains (case-insensitive substring) ──────────────────────────
    private String speaker;
    private String producer;
    private String composer;
    private String poet;
    private String lyrics;
    private String recordingVenue;
    private String locationArchive;
    private String degitizedBy;
    private String degitizationEquipment;
    private String provenance;
    private String copyright;
    private String rightOwner;
    private String usageRights;
    private String owner;
    private String publisher;

    // ─── Collections (any|all) ────────────────────────────────────────────────────
    private List<String> genre;
    private String genreMatch;
    private List<String> contributors;
    private String contributorMatch;
    private List<String> tags;
    private String tagMatch;
    private List<String> keywords;
    private String keywordMatch;

    // ─── Boolean ──────────────────────────────────────────────────────────────────
    private Boolean physicalAvailability;

    // ─── Numeric ranges ───────────────────────────────────────────────────────────
    private Integer audioQualityMin;
    private Integer audioQualityMax;
    private Integer versionNumberMin;
    private Integer versionNumberMax;
    private Integer copyNumberMin;
    private Integer copyNumberMax;

    // ─── Date ranges (ISO-8601 instants) ──────────────────────────────────────────
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateCreatedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateCreatedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant datePublishedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant datePublishedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateModifiedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateModifiedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateCopyrightedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant dateCopyrightedTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant createdFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant createdTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant updatedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant updatedTo;

    public boolean isEmpty() {
        return blank(sortBy) && blank(sortDirection)
                && blank(form) && blank(typeOfBasta) && blank(typeOfMaqam)
                && blank(language) && blank(dialect)
                && blank(typeOfComposition) && blank(typeOfPerformance)
                && blank(city) && blank(region) && blank(audience)
                && blank(audioChannel) && blank(fileExtension)
                && blank(bitRate) && blank(bitDepth) && blank(sampleRate)
                && blank(lccClassification) && blank(accrualMethod)
                && blank(availability) && blank(licenseType)
                && blank(speaker) && blank(producer) && blank(composer)
                && blank(poet) && blank(lyrics) && blank(recordingVenue)
                && blank(locationArchive) && blank(degitizedBy) && blank(degitizationEquipment)
                && blank(provenance) && blank(copyright) && blank(rightOwner)
                && blank(usageRights) && blank(owner) && blank(publisher)
                && empty(genre) && empty(contributors) && empty(tags) && empty(keywords)
                && physicalAvailability == null
                && audioQualityMin == null && audioQualityMax == null
                && versionNumberMin == null && versionNumberMax == null
                && copyNumberMin == null && copyNumberMax == null
                && dateCreatedFrom == null && dateCreatedTo == null
                && datePublishedFrom == null && datePublishedTo == null
                && dateModifiedFrom == null && dateModifiedTo == null
                && dateCopyrightedFrom == null && dateCopyrightedTo == null
                && createdFrom == null && createdTo == null
                && updatedFrom == null && updatedTo == null;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean empty(List<?> l) { return l == null || l.isEmpty(); }
}
