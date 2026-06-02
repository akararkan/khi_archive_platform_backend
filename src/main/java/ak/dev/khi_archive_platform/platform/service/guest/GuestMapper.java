package ak.dev.khi_archive_platform.platform.service.guest;

import ak.dev.khi_archive_platform.platform.dto.guest.GuestAudioDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestCategoryDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestCategorySummaryDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestImageDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestPersonDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestPersonSummaryDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestProjectDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestProjectSummaryDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestTextDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestVideoDTO;
import ak.dev.khi_archive_platform.platform.model.audio.Audio;
import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.model.image.Image;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.model.text.Text;
import ak.dev.khi_archive_platform.platform.model.video.Video;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateless entity → guest-DTO mappers. One method per entity. Drops
 * technical fields (lcc, bit-rate, paths, volumes, version internals) and
 * audit/trash bookkeeping (createdBy, removedAt, version) from every shape.
 */
final class GuestMapper {

    private GuestMapper() {}

    /**
     * Materializes a Hibernate lazy collection into a plain {@link ArrayList}
     * so it can outlive the JPA session — Jackson serializes guest DTOs after
     * the transactional service method has returned, so any
     * {@code @ElementCollection} or {@code @ManyToMany} list passed by
     * reference would explode with {@code LazyInitializationException}.
     */
    private static <T> List<T> copyList(List<T> in) {
        return (in == null || in.isEmpty()) ? List.of() : new ArrayList<>(in);
    }

    /** Maps a project's category list into summary stubs, materializing the lazy collection. */
    private static List<GuestCategorySummaryDTO> projectCategories(Project p) {
        if (p == null) return List.of();
        List<Category> cats = p.getCategories();
        if (cats == null || cats.isEmpty()) return List.of();
        return cats.stream().map(GuestMapper::toCategorySummary).toList();
    }

    // ─── Project ──────────────────────────────────────────────────────────────────

    static GuestProjectDTO toProject(Project p, GuestProjectDTO.MediaCounts counts) {
        if (p == null) return null;
        Person person = p.getPerson();
        List<Category> cats = p.getCategories();
        return GuestProjectDTO.builder()
                .id(p.getId())
                .projectCode(p.getProjectCode())
                .projectName(p.getProjectName())
                .description(p.getDescription())
                .tags(copyList(p.getTags()))
                .keywords(copyList(p.getKeywords()))
                .person(person == null ? null : toPersonSummary(person))
                .categories(cats == null ? List.of() : cats.stream().map(GuestMapper::toCategorySummary).toList())
                .mediaCounts(counts)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    static GuestProjectSummaryDTO toProjectSummary(Project p) {
        if (p == null) return null;
        return GuestProjectSummaryDTO.builder()
                .id(p.getId())
                .projectCode(p.getProjectCode())
                .projectName(p.getProjectName())
                .build();
    }

    // ─── Category ─────────────────────────────────────────────────────────────────

    static GuestCategoryDTO toCategory(Category c, long projectCount) {
        if (c == null) return null;
        return GuestCategoryDTO.builder()
                .id(c.getId())
                .categoryCode(c.getCategoryCode())
                .name(c.getName())
                .description(c.getDescription())
                .keywords(copyList(c.getKeywords()))
                .projectCount(projectCount)
                .createdAt(c.getCreatedAt())
                .build();
    }

    static GuestCategorySummaryDTO toCategorySummary(Category c) {
        if (c == null) return null;
        return GuestCategorySummaryDTO.builder()
                .id(c.getId())
                .categoryCode(c.getCategoryCode())
                .name(c.getName())
                .build();
    }

    // ─── Person ───────────────────────────────────────────────────────────────────

    static GuestPersonDTO toPerson(Person p, long projectCount) {
        if (p == null) return null;
        return GuestPersonDTO.builder()
                .id(p.getId())
                .personCode(p.getPersonCode())
                .mediaPortrait(p.getMediaPortrait())
                .fullName(p.getFullName())
                .nickname(p.getNickname())
                .romanizedName(p.getRomanizedName())
                .gender(p.getGender())
                .personType(copyList(p.getPersonType()))
                .region(p.getRegion())
                .dateOfBirth(p.getDateOfBirth())
                .dateOfBirthPrecision(p.getDateOfBirthPrecision())
                .placeOfBirth(p.getPlaceOfBirth())
                .dateOfDeath(p.getDateOfDeath())
                .dateOfDeathPrecision(p.getDateOfDeathPrecision())
                .placeOfDeath(p.getPlaceOfDeath())
                .description(p.getDescription())
                .projectCount(projectCount)
                .build();
    }

    static GuestPersonSummaryDTO toPersonSummary(Person p) {
        if (p == null) return null;
        return GuestPersonSummaryDTO.builder()
                .id(p.getId())
                .personCode(p.getPersonCode())
                .fullName(p.getFullName())
                .nickname(p.getNickname())
                .romanizedName(p.getRomanizedName())
                .mediaPortrait(p.getMediaPortrait())
                .build();
    }

    // ─── Audio ────────────────────────────────────────────────────────────────────

    static GuestAudioDTO toAudio(Audio a) {
        if (a == null) return null;
        Project project = a.getProject();
        return GuestAudioDTO.builder()
                .id(a.getId())
                .audioCode(a.getAudioCode())
                .projectCode(project == null ? null : project.getProjectCode())
                .projectName(project == null ? null : project.getProjectName())
                .personMediaPortrait(project == null || project.getPerson() == null ? null : project.getPerson().getMediaPortrait())
                .person(project == null ? null : toPersonSummary(project.getPerson()))
                .categories(projectCategories(project))
                .originTitle(a.getOriginTitle())
                .alterTitle(a.getAlterTitle())
                .centralKurdishTitle(a.getCentral_kurdish_title())
                .romanizedTitle(a.getRomanized_title())
                .form(a.getForm())
                .typeOfBasta(a.getTypeOfBasta())
                .typeOfMaqam(a.getTypeOfMaqam())
                .genre(copyList(a.getGenre()))
                .abstractText(a.getAbstractText())
                .description(a.getDescription())
                .speaker(a.getSpeaker())
                .producer(a.getProducer())
                .composer(a.getComposer())
                .poet(a.getPoet())
                .contributors(copyList(a.getContributors()))
                .language(a.getLanguage())
                .dialect(a.getDialect())
                .typeOfComposition(a.getTypeOfComposition())
                .typeOfPerformance(a.getTypeOfPerformance())
                .lyrics(a.getLyrics())
                .recordingVenue(a.getRecording_venue())
                .city(a.getCity())
                .region(a.getRegion())
                .audience(a.getAudience())
                .tags(copyList(a.getTags()))
                .keywords(copyList(a.getKeywords()))
                .dateCreated(a.getDate_created())
                .datePublished(a.getDate_published())
                .dateModified(a.getDate_modified())
                .copyright(a.getCopyright())
                .rightOwner(a.getRightOwner())
                .dateCopyrighted(a.getDateCopyRighted())
                .licenseType(a.getLicenseType())
                .availability(a.getAvailability())
                .owner(a.getOwner())
                .publisher(a.getPublisher())
                .audioFileUrl(a.getAudioFileUrl())
                .build();
    }

    // ─── Video ────────────────────────────────────────────────────────────────────

    static GuestVideoDTO toVideo(Video v) {
        if (v == null) return null;
        Project project = v.getProject();
        return GuestVideoDTO.builder()
                .id(v.getId())
                .videoCode(v.getVideoCode())
                .projectCode(project == null ? null : project.getProjectCode())
                .projectName(project == null ? null : project.getProjectName())
                .personMediaPortrait(project == null || project.getPerson() == null ? null : project.getPerson().getMediaPortrait())
                .person(project == null ? null : toPersonSummary(project.getPerson()))
                .categories(projectCategories(project))
                .originalTitle(v.getOriginalTitle())
                .alternativeTitle(v.getAlternativeTitle())
                .titleInCentralKurdish(v.getTitleInCentralKurdish())
                .romanizedTitle(v.getRomanizedTitle())
                .subject(copyList(v.getSubject()))
                .genre(copyList(v.getGenre()))
                .event(v.getEvent())
                .location(v.getLocation())
                .description(v.getDescription())
                .personShownInVideo(v.getPersonShownInVideo())
                .colorOfVideo(copyList(v.getColorOfVideo()))
                .language(v.getLanguage())
                .dialect(v.getDialect())
                .subtitle(v.getSubtitle())
                .creatorArtistDirector(v.getCreatorArtistDirector())
                .producer(v.getProducer())
                .contributor(v.getContributor())
                .audience(v.getAudience())
                .tags(copyList(v.getTags()))
                .keywords(copyList(v.getKeywords()))
                .whereThisVideoUsed(copyList(v.getWhereThisVideoUsed()))
                .duration(v.getDuration())
                .dateCreated(v.getDateCreated())
                .dateModified(v.getDateModified())
                .datePublished(v.getDatePublished())
                .copyright(v.getCopyright())
                .rightOwner(v.getRightOwner())
                .dateCopyrighted(v.getDateCopyrighted())
                .licenseType(v.getLicenseType())
                .usageRights(v.getUsageRights())
                .availability(v.getAvailability())
                .owner(v.getOwner())
                .publisher(v.getPublisher())
                .videoFileUrl(v.getVideoFileUrl())
                .build();
    }

    // ─── Text ─────────────────────────────────────────────────────────────────────

    static GuestTextDTO toText(Text t) {
        if (t == null) return null;
        Project project = t.getProject();
        return GuestTextDTO.builder()
                .id(t.getId())
                .textCode(t.getTextCode())
                .projectCode(project == null ? null : project.getProjectCode())
                .projectName(project == null ? null : project.getProjectName())
                .personMediaPortrait(project == null || project.getPerson() == null ? null : project.getPerson().getMediaPortrait())
                .person(project == null ? null : toPersonSummary(project.getPerson()))
                .categories(projectCategories(project))
                .originalTitle(t.getOriginalTitle())
                .alternativeTitle(t.getAlternativeTitle())
                .titleInCentralKurdish(t.getTitleInCentralKurdish())
                .romanizedTitle(t.getRomanizedTitle())
                .subject(copyList(t.getSubject()))
                .genre(copyList(t.getGenre()))
                .documentType(t.getDocumentType())
                .description(t.getDescription())
                .script(t.getScript())
                .transcription(t.getTranscription())
                .isbn(t.getIsbn())
                .edition(t.getEdition())
                .volume(t.getVolume())
                .series(t.getSeries())
                .language(t.getLanguage())
                .dialect(t.getDialect())
                .author(t.getAuthor())
                .contributors(t.getContributors())
                .printingHouse(t.getPrintingHouse())
                .audience(t.getAudience())
                .tags(copyList(t.getTags()))
                .keywords(copyList(t.getKeywords()))
                .pageCount(t.getPageCount())
                .dateCreated(t.getDateCreated())
                .printDate(t.getPrintDate())
                .dateModified(t.getDateModified())
                .datePublished(t.getDatePublished())
                .copyright(t.getCopyright())
                .rightOwner(t.getRightOwner())
                .dateCopyrighted(t.getDateCopyrighted())
                .licenseType(t.getLicenseType())
                .usageRights(t.getUsageRights())
                .availability(t.getAvailability())
                .owner(t.getOwner())
                .publisher(t.getPublisher())
                .textFileUrl(t.getTextFileUrl())
                .build();
    }

    // ─── Image ────────────────────────────────────────────────────────────────────

    static GuestImageDTO toImage(Image i) {
        if (i == null) return null;
        Project project = i.getProject();
        return GuestImageDTO.builder()
                .id(i.getId())
                .imageCode(i.getImageCode())
                .projectCode(project == null ? null : project.getProjectCode())
                .projectName(project == null ? null : project.getProjectName())
                .personMediaPortrait(project == null || project.getPerson() == null ? null : project.getPerson().getMediaPortrait())
                .person(project == null ? null : toPersonSummary(project.getPerson()))
                .categories(projectCategories(project))
                .originalTitle(i.getOriginalTitle())
                .alternativeTitle(i.getAlternativeTitle())
                .titleInCentralKurdish(i.getTitleInCentralKurdish())
                .romanizedTitle(i.getRomanizedTitle())
                .subject(copyList(i.getSubject()))
                .form(i.getForm())
                .genre(copyList(i.getGenre()))
                .event(i.getEvent())
                .location(i.getLocation())
                .description(i.getDescription())
                .personShownInImage(i.getPersonShownInImage())
                .colorOfImage(copyList(i.getColorOfImage()))
                .manufacturer(i.getManufacturer())
                .model(i.getModel())
                .lens(i.getLens())
                .creatorArtistPhotographer(i.getCreatorArtistPhotographer())
                .contributor(i.getContributor())
                .audience(i.getAudience())
                .photostory(i.getPhotostory())
                .tags(copyList(i.getTags()))
                .keywords(copyList(i.getKeywords()))
                .whereThisImageUsed(copyList(i.getWhereThisImageUsed()))
                .dateCreated(i.getDateCreated())
                .dateModified(i.getDateModified())
                .datePublished(i.getDatePublished())
                .copyright(i.getCopyright())
                .rightOwner(i.getRightOwner())
                .dateCopyrighted(i.getDateCopyrighted())
                .licenseType(i.getLicenseType())
                .usageRights(i.getUsageRights())
                .availability(i.getAvailability())
                .owner(i.getOwner())
                .publisher(i.getPublisher())
                .imageFileUrl(i.getImageFileUrl())
                .build();
    }
}
