package ak.dev.khi_archive_platform.platform.seed;

import ak.dev.khi_archive_platform.platform.enums.DatePrecision;
import ak.dev.khi_archive_platform.platform.enums.Gender;
import ak.dev.khi_archive_platform.platform.model.audio.Audio;
import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.model.image.Image;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.model.text.Text;
import ak.dev.khi_archive_platform.platform.model.video.Video;
import ak.dev.khi_archive_platform.platform.repo.audio.AudioRepository;
import ak.dev.khi_archive_platform.platform.repo.category.CategoryRepository;
import ak.dev.khi_archive_platform.platform.repo.image.ImageRepository;
import ak.dev.khi_archive_platform.platform.repo.person.PersonRepository;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import ak.dev.khi_archive_platform.platform.repo.text.TextRepository;
import ak.dev.khi_archive_platform.platform.repo.video.VideoRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads {@code seed-data/*.json} into the dev database. Idempotent — records
 * whose business code already exists are skipped, so reruns are safe.
 *
 * <p>Activated by {@code app.seed.load=true}. Off by default so it never
 * runs in production.
 *
 * <p>Order: categories → persons → projects → audios/videos/texts/images.
 * That respects every FK in the schema.
 */
@Component
@ConditionalOnProperty(name = "app.seed.load", havingValue = "true")
public class SeedDataLoader implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SeedDataLoader.class);
    private static final int MEDIA_PORTRAIT_MAX = 255;

    private final CategoryRepository categoryRepo;
    private final PersonRepository personRepo;
    private final ProjectRepository projectRepo;
    private final AudioRepository audioRepo;
    private final VideoRepository videoRepo;
    private final TextRepository textRepo;
    private final ImageRepository imageRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.seed.dir:./seed-data}")
    private String seedDir;

    public SeedDataLoader(CategoryRepository categoryRepo, PersonRepository personRepo,
                          ProjectRepository projectRepo, AudioRepository audioRepo,
                          VideoRepository videoRepo, TextRepository textRepo,
                          ImageRepository imageRepo) {
        this.categoryRepo = categoryRepo;
        this.personRepo = personRepo;
        this.projectRepo = projectRepo;
        this.audioRepo = audioRepo;
        this.videoRepo = videoRepo;
        this.textRepo = textRepo;
        this.imageRepo = imageRepo;
    }

    @Override
    public void run(String... args) throws Exception {
        File dir = new File(seedDir);
        if (!dir.isDirectory()) {
            LOG.warn("Seed dir not found: {} — skipping load.", dir.getAbsolutePath());
            return;
        }
        LOG.info("=== SEED LOAD START — reading from {} ===", dir.getAbsolutePath());

        int cats = loadCategories(new File(dir, "categories.json"));
        int persons = loadPersons(new File(dir, "persons.json"));
        int projects = loadProjects(new File(dir, "projects.json"));
        int audios = loadAudios(new File(dir, "audios.json"));
        int videos = loadVideos(new File(dir, "videos.json"));
        int texts = loadTexts(new File(dir, "texts.json"));
        int images = loadImages(new File(dir, "images.json"));

        LOG.info("=== SEED LOAD DONE ===");
        LOG.info("  categories inserted: {}  (total in DB: {})", cats, categoryRepo.count());
        LOG.info("  persons    inserted: {}  (total in DB: {})", persons, personRepo.count());
        LOG.info("  projects   inserted: {}  (total in DB: {})", projects, projectRepo.count());
        LOG.info("  audios     inserted: {}  (total in DB: {})", audios, audioRepo.count());
        LOG.info("  videos     inserted: {}  (total in DB: {})", videos, videoRepo.count());
        LOG.info("  texts      inserted: {}  (total in DB: {})", texts, textRepo.count());
        LOG.info("  images     inserted: {}  (total in DB: {})", images, imageRepo.count());
    }

    // ─── Loaders ─────────────────────────────────────────────────────────────

    @Transactional
    protected int loadCategories(File f) throws Exception {
        if (!f.exists()) return 0;
        List<Map<String, Object>> rows = mapper.readValue(f, new TypeReference<>() {});
        int inserted = 0;
        for (Map<String, Object> r : rows) {
            String code = str(r, "categoryCode");
            if (code == null || categoryRepo.findByCategoryCode(code).isPresent()) continue;
            Category c = Category.builder()
                    .categoryCode(code)
                    .name(str(r, "name"))
                    .description(str(r, "description"))
                    .keywords(listOfStr(r, "keywords"))
                    .createdAt(asInstant(r.get("createdAt")))
                    .build();
            categoryRepo.save(c);
            inserted++;
        }
        LOG.info("loadCategories: read={} inserted={}", rows.size(), inserted);
        return inserted;
    }

    @Transactional
    protected int loadPersons(File f) throws Exception {
        if (!f.exists()) return 0;
        List<Map<String, Object>> rows = mapper.readValue(f, new TypeReference<>() {});
        int inserted = 0;
        for (Map<String, Object> r : rows) {
            String code = str(r, "personCode");
            if (code == null || personRepo.findByPersonCode(code).isPresent()) continue;
            String portrait = str(r, "mediaPortrait");
            if (portrait != null && portrait.length() > MEDIA_PORTRAIT_MAX) {
                // mediaPortrait column is varchar(255). For real-world Wikipedia
                // URLs with Persian/Kurdish-encoded filenames we'd overflow, so
                // fall back to a short deterministic placeholder instead of
                // mangling the original by truncation.
                portrait = "https://picsum.photos/seed/" + code + "/512/512";
            }
            Person p = Person.builder()
                    .personCode(code)
                    .mediaPortrait(portrait)
                    .fullName(str(r, "fullName"))
                    .nickname(str(r, "nickname"))
                    .romanizedName(str(r, "romanizedName"))
                    .gender(parseGender(str(r, "gender")))
                    .personType(listOfStr(r, "personType"))
                    .region(str(r, "region"))
                    .dateOfBirth(asLocalDate(r.get("dateOfBirth")))
                    .dateOfBirthPrecision(parseDatePrecision(str(r, "dateOfBirthPrecision")))
                    .placeOfBirth(str(r, "placeOfBirth"))
                    .dateOfDeath(asLocalDate(r.get("dateOfDeath")))
                    .dateOfDeathPrecision(parseDatePrecision(str(r, "dateOfDeathPrecision")))
                    .placeOfDeath(str(r, "placeOfDeath"))
                    .description(str(r, "description"))
                    .build();
            personRepo.save(p);
            inserted++;
        }
        LOG.info("loadPersons: read={} inserted={}", rows.size(), inserted);
        return inserted;
    }

    @Transactional
    protected int loadProjects(File f) throws Exception {
        if (!f.exists()) return 0;
        List<Map<String, Object>> rows = mapper.readValue(f, new TypeReference<>() {});

        // Build code → entity lookups once, to avoid 500 individual SELECTs.
        Map<String, Person> personByCode = new HashMap<>();
        personRepo.findAll().forEach(p -> personByCode.put(p.getPersonCode(), p));
        Map<String, Category> catByCode = new HashMap<>();
        categoryRepo.findAll().forEach(c -> catByCode.put(c.getCategoryCode(), c));

        int inserted = 0;
        for (Map<String, Object> r : rows) {
            String code = str(r, "projectCode");
            if (code == null || projectRepo.findByProjectCode(code).isPresent()) continue;

            Map<String, Object> personJson = mapOrNull(r.get("person"));
            Person person = personJson == null ? null : personByCode.get(str(personJson, "personCode"));

            List<Map<String, Object>> catList = listOfMap(r.get("categories"));
            List<Category> cats = new ArrayList<>();
            for (Map<String, Object> cj : catList) {
                Category c = catByCode.get(str(cj, "categoryCode"));
                if (c != null) cats.add(c);
            }
            if (cats.isEmpty()) continue; // Project requires ≥1 category

            Project proj = Project.builder()
                    .projectCode(code)
                    .projectName(str(r, "projectName"))
                    .person(person)
                    .categories(cats)
                    .description(str(r, "description"))
                    .tags(listOfStr(r, "tags"))
                    .keywords(listOfStr(r, "keywords"))
                    .createdAt(asInstant(r.get("createdAt")))
                    .updatedAt(asInstant(r.get("updatedAt")))
                    .build();
            projectRepo.save(proj);
            inserted++;
        }
        LOG.info("loadProjects: read={} inserted={}", rows.size(), inserted);
        return inserted;
    }

    @Transactional
    protected int loadAudios(File f) throws Exception {
        if (!f.exists()) return 0;
        List<Map<String, Object>> rows = mapper.readValue(f, new TypeReference<>() {});
        Map<String, Project> projByCode = new HashMap<>();
        projectRepo.findAll().forEach(p -> projByCode.put(p.getProjectCode(), p));

        int inserted = 0;
        for (Map<String, Object> r : rows) {
            String code = str(r, "audioCode");
            if (code == null || audioRepo.findByAudioCode(code).isPresent()) continue;
            Project proj = projByCode.get(str(r, "projectCode"));
            if (proj == null) continue;

            Audio a = Audio.builder()
                    .audioCode(code)
                    .project(proj)
                    .originTitle(str(r, "originTitle"))
                    .alterTitle(str(r, "alterTitle"))
                    .central_kurdish_title(str(r, "centralKurdishTitle"))
                    .romanized_title(str(r, "romanizedTitle"))
                    .form(str(r, "form"))
                    .typeOfBasta(str(r, "typeOfBasta"))
                    .typeOfMaqam(str(r, "typeOfMaqam"))
                    .genre(listOfStr(r, "genre"))
                    .abstractText(str(r, "abstractText"))
                    .description(str(r, "description"))
                    .speaker(str(r, "speaker"))
                    .producer(str(r, "producer"))
                    .composer(str(r, "composer"))
                    .poet(str(r, "poet"))
                    .contributors(listOfStr(r, "contributors"))
                    .language(str(r, "language"))
                    .dialect(str(r, "dialect"))
                    .typeOfComposition(str(r, "typeOfComposition"))
                    .typeOfPerformance(str(r, "typeOfPerformance"))
                    .lyrics(str(r, "lyrics"))
                    .recording_venue(str(r, "recordingVenue"))
                    .city(str(r, "city"))
                    .region(str(r, "region"))
                    .audience(str(r, "audience"))
                    .tags(listOfStr(r, "tags"))
                    .keywords(listOfStr(r, "keywords"))
                    .date_created(asInstant(r.get("dateCreated")))
                    .date_published(asInstant(r.get("datePublished")))
                    .date_modified(asInstant(r.get("dateModified")))
                    .copyright(str(r, "copyright"))
                    .rightOwner(str(r, "rightOwner"))
                    .dateCopyRighted(asInstant(r.get("dateCopyrighted")))
                    .licenseType(str(r, "licenseType"))
                    .availability(str(r, "availability"))
                    .owner(str(r, "owner"))
                    .publisher(str(r, "publisher"))
                    .audioFileUrl(str(r, "audioFileUrl"))
                    .physicalAvailability(false)
                    .build();
            audioRepo.save(a);
            inserted++;
        }
        LOG.info("loadAudios: read={} inserted={}", rows.size(), inserted);
        return inserted;
    }

    @Transactional
    protected int loadVideos(File f) throws Exception {
        if (!f.exists()) return 0;
        List<Map<String, Object>> rows = mapper.readValue(f, new TypeReference<>() {});
        Map<String, Project> projByCode = new HashMap<>();
        projectRepo.findAll().forEach(p -> projByCode.put(p.getProjectCode(), p));

        int inserted = 0;
        for (Map<String, Object> r : rows) {
            String code = str(r, "videoCode");
            if (code == null || videoRepo.findByVideoCode(code).isPresent()) continue;
            Project proj = projByCode.get(str(r, "projectCode"));
            if (proj == null) continue;

            Video v = Video.builder()
                    .videoCode(code)
                    .project(proj)
                    .originalTitle(str(r, "originalTitle"))
                    .alternativeTitle(str(r, "alternativeTitle"))
                    .titleInCentralKurdish(str(r, "titleInCentralKurdish"))
                    .romanizedTitle(str(r, "romanizedTitle"))
                    .subject(listOfStr(r, "subject"))
                    .genre(listOfStr(r, "genre"))
                    .event(str(r, "event"))
                    .location(str(r, "location"))
                    .description(str(r, "description"))
                    .personShownInVideo(str(r, "personShownInVideo"))
                    .colorOfVideo(listOfStr(r, "colorOfVideo"))
                    .language(str(r, "language"))
                    .dialect(str(r, "dialect"))
                    .subtitle(str(r, "subtitle"))
                    .creatorArtistDirector(str(r, "creatorArtistDirector"))
                    .producer(str(r, "producer"))
                    .contributor(str(r, "contributor"))
                    .audience(str(r, "audience"))
                    .tags(listOfStr(r, "tags"))
                    .keywords(listOfStr(r, "keywords"))
                    .whereThisVideoUsed(listOfStr(r, "whereThisVideoUsed"))
                    .duration(str(r, "duration"))
                    .dateCreated(asInstant(r.get("dateCreated")))
                    .dateModified(asInstant(r.get("dateModified")))
                    .datePublished(asInstant(r.get("datePublished")))
                    .copyright(str(r, "copyright"))
                    .rightOwner(str(r, "rightOwner"))
                    .dateCopyrighted(asInstant(r.get("dateCopyrighted")))
                    .licenseType(str(r, "licenseType"))
                    .usageRights(str(r, "usageRights"))
                    .availability(str(r, "availability"))
                    .owner(str(r, "owner"))
                    .publisher(str(r, "publisher"))
                    .videoFileUrl(str(r, "videoFileUrl"))
                    .physicalAvailability(false)
                    .build();
            videoRepo.save(v);
            inserted++;
        }
        LOG.info("loadVideos: read={} inserted={}", rows.size(), inserted);
        return inserted;
    }

    @Transactional
    protected int loadTexts(File f) throws Exception {
        if (!f.exists()) return 0;
        List<Map<String, Object>> rows = mapper.readValue(f, new TypeReference<>() {});
        Map<String, Project> projByCode = new HashMap<>();
        projectRepo.findAll().forEach(p -> projByCode.put(p.getProjectCode(), p));

        int inserted = 0;
        for (Map<String, Object> r : rows) {
            String code = str(r, "textCode");
            if (code == null || textRepo.findByTextCode(code).isPresent()) continue;
            Project proj = projByCode.get(str(r, "projectCode"));
            if (proj == null) continue;

            Text t = Text.builder()
                    .textCode(code)
                    .project(proj)
                    .originalTitle(str(r, "originalTitle"))
                    .alternativeTitle(str(r, "alternativeTitle"))
                    .titleInCentralKurdish(str(r, "titleInCentralKurdish"))
                    .romanizedTitle(str(r, "romanizedTitle"))
                    .subject(listOfStr(r, "subject"))
                    .genre(listOfStr(r, "genre"))
                    .documentType(str(r, "documentType"))
                    .description(str(r, "description"))
                    .script(str(r, "script"))
                    .transcription(str(r, "transcription"))
                    .isbn(str(r, "isbn"))
                    .edition(str(r, "edition"))
                    .volume(str(r, "volume"))
                    .series(str(r, "series"))
                    .language(str(r, "language"))
                    .dialect(str(r, "dialect"))
                    .author(str(r, "author"))
                    .contributors(str(r, "contributors"))
                    .printingHouse(str(r, "printingHouse"))
                    .audience(str(r, "audience"))
                    .tags(listOfStr(r, "tags"))
                    .keywords(listOfStr(r, "keywords"))
                    .pageCount(asInt(r.get("pageCount")))
                    .dateCreated(asInstant(r.get("dateCreated")))
                    .printDate(asInstant(r.get("printDate")))
                    .dateModified(asInstant(r.get("dateModified")))
                    .datePublished(asInstant(r.get("datePublished")))
                    .copyright(str(r, "copyright"))
                    .rightOwner(str(r, "rightOwner"))
                    .dateCopyrighted(asInstant(r.get("dateCopyrighted")))
                    .licenseType(str(r, "licenseType"))
                    .usageRights(str(r, "usageRights"))
                    .availability(str(r, "availability"))
                    .owner(str(r, "owner"))
                    .publisher(str(r, "publisher"))
                    .textFileUrl(str(r, "textFileUrl"))
                    .physicalAvailability(false)
                    .build();
            textRepo.save(t);
            inserted++;
        }
        LOG.info("loadTexts: read={} inserted={}", rows.size(), inserted);
        return inserted;
    }

    @Transactional
    protected int loadImages(File f) throws Exception {
        if (!f.exists()) return 0;
        List<Map<String, Object>> rows = mapper.readValue(f, new TypeReference<>() {});
        Map<String, Project> projByCode = new HashMap<>();
        projectRepo.findAll().forEach(p -> projByCode.put(p.getProjectCode(), p));

        int inserted = 0;
        for (Map<String, Object> r : rows) {
            String code = str(r, "imageCode");
            if (code == null || imageRepo.findByImageCode(code).isPresent()) continue;
            Project proj = projByCode.get(str(r, "projectCode"));
            if (proj == null) continue;

            Image i = Image.builder()
                    .imageCode(code)
                    .project(proj)
                    .originalTitle(str(r, "originalTitle"))
                    .alternativeTitle(str(r, "alternativeTitle"))
                    .titleInCentralKurdish(str(r, "titleInCentralKurdish"))
                    .romanizedTitle(str(r, "romanizedTitle"))
                    .subject(listOfStr(r, "subject"))
                    .form(str(r, "form"))
                    .genre(listOfStr(r, "genre"))
                    .event(str(r, "event"))
                    .location(str(r, "location"))
                    .description(str(r, "description"))
                    .personShownInImage(str(r, "personShownInImage"))
                    .colorOfImage(listOfStr(r, "colorOfImage"))
                    .manufacturer(str(r, "manufacturer"))
                    .model(str(r, "model"))
                    .lens(str(r, "lens"))
                    .creatorArtistPhotographer(str(r, "creatorArtistPhotographer"))
                    .contributor(str(r, "contributor"))
                    .audience(str(r, "audience"))
                    .photostory(str(r, "photostory"))
                    .tags(listOfStr(r, "tags"))
                    .keywords(listOfStr(r, "keywords"))
                    .whereThisImageUsed(listOfStr(r, "whereThisImageUsed"))
                    .dateCreated(asInstant(r.get("dateCreated")))
                    .dateModified(asInstant(r.get("dateModified")))
                    .datePublished(asInstant(r.get("datePublished")))
                    .copyright(str(r, "copyright"))
                    .rightOwner(str(r, "rightOwner"))
                    .dateCopyrighted(asInstant(r.get("dateCopyrighted")))
                    .licenseType(str(r, "licenseType"))
                    .usageRights(str(r, "usageRights"))
                    .availability(str(r, "availability"))
                    .owner(str(r, "owner"))
                    .publisher(str(r, "publisher"))
                    .imageFileUrl(str(r, "imageFileUrl"))
                    .physicalAvailability(false)
                    .build();
            imageRepo.save(i);
            inserted++;
        }
        LOG.info("loadImages: read={} inserted={}", rows.size(), inserted);
        return inserted;
    }

    // ─── JSON helpers ────────────────────────────────────────────────────────

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static Instant asInstant(Object v) {
        if (v == null) return null;
        try {
            return Instant.parse(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate asLocalDate(Object v) {
        if (v == null) return null;
        try {
            return LocalDate.parse(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static Gender parseGender(String v) {
        if (v == null) return null;
        try {
            return Gender.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static DatePrecision parseDatePrecision(String v) {
        if (v == null) return null;
        try {
            return DatePrecision.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOfStr(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof List<?> raw)) return new ArrayList<>();
        List<String> out = new ArrayList<>(raw.size());
        for (Object e : raw) if (e != null) out.add(e.toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMap(Object v) {
        if (!(v instanceof List<?> raw)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : raw) if (e instanceof Map<?, ?> mm) out.add((Map<String, Object>) mm);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrNull(Object v) {
        return (v instanceof Map<?, ?> mm) ? (Map<String, Object>) mm : null;
    }
}
