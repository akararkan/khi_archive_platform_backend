#!/usr/bin/env python3
"""
Generates 5 test fixtures for bulk endpoints:
  - test-projects-1000.json    -> POST /api/project/bulk
  - test-images-1000.json      -> POST /api/image/bulk
  - test-texts-1000.json       -> POST /api/text/bulk
  - test-videos-1000.json      -> POST /api/video/bulk
  - test-audios-1000.json      -> POST /api/audio/bulk

Strategy:
  * 1000 untitled projects (no person), each tied to one category from
    test-categories-1000.json (round-robin).
  * Project codes will be auto-generated as UNTITLED_PROJ_000001 ..
    UNTITLED_PROJ_001000 by the server. Media fixtures reference these codes.
  * Each project gets exactly one image, text, video, audio record so media
    fixtures total 4000 rows (1000 each).
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATEGORIES = os.path.join(ROOT, "test-categories-1000.json")
N = 1000

with open(CATEGORIES) as f:
    cats = json.load(f)
assert len(cats) >= N, f"expected >= {N} categories, got {len(cats)}"

# Sample varied titles and Kurdish/multilingual content to exercise the search.
KURDISH_TITLES = [
    "گۆڤاری هاوار", "دیوانی نالی", "مەم و زین", "دەنگی هەڵبجە",
    "میژووی کوردستان", "مۆرکی شەهیدان", "لاوژەی شار", "سترانی فۆلکلۆر",
]
ROMANIZED_TITLES = [
    "Govari Hawar", "Diwani Nali", "Mem u Zin", "Dengi Halabja",
    "Mizhuy Kurdistan", "Morki Shahidan", "Lawzhey Shar", "Stranani Folklore",
]
LANGUAGES = ["Kurdish", "Sorani", "Kurmanji", "Arabic", "Persian", "English"]
DIALECTS = ["Sorani", "Kurmanji", "Hawrami", "Zaza", "Mukri", "Garmiyani"]
LOCATIONS = ["Hawler", "Sulaimani", "Halabja", "Duhok", "Kirkuk", "Mahabad", "Diyarbakir", "Qamishlo"]
EVENTS = ["Newroz Celebration", "Halabja Memorial", "Kurdistan Cultural Festival",
          "Sulaimani Book Fair", "Hawler Documentary Days", "Folklore Symposium"]
SUBJECTS = ["Folklore", "Music", "Literature", "Politics", "History", "Religion",
            "Ethnography", "Archaeology", "Cinema", "Theatre"]
GENRES = ["Documentary", "Folk", "Classical", "Modern", "Traditional", "Memoir",
          "Poetry", "Prose", "Speech", "Interview"]
COLORS = ["Black-and-White", "Sepia", "Color", "Hand-coloured"]
EQUIPMENT = ["Canon EOS 5D", "Nikon D850", "Hasselblad H6D", "Leica M11", "Sony A7R IV"]
PEOPLE = ["Jigarkhwen", "Hejar", "Hemin", "Goran", "Sherko Bekas", "Bachtyar Ali",
          "Mahwi", "Nali", "Salim", "Faiq Bekas", "Mohammad Mokri", "Ehmedi Xani"]
PUBLISHERS = ["KHI Press", "Aras Publishing", "Sardam", "Awa", "Roj Publishing"]


def cat_code(i: int) -> str:
    return cats[i % len(cats)]["categoryCode"]


def cat_name(i: int) -> str:
    return cats[i % len(cats)]["name"]


def project_code(i: int) -> str:
    """Server auto-generates these — must match: UNTITLED_PROJ_000001 ..."""
    return f"UNTITLED_PROJ_{i:06d}"


def projects():
    out = []
    for i in range(1, N + 1):
        out.append({
            "projectName": f"Collection of {cat_name(i - 1)} (Vol {i})",
            "personCode": None,
            "categoryCodes": [cat_code(i - 1)],
            "description": f"Bulk-loaded archive collection #{i} covering {cat_name(i - 1)}.",
            "tags": ["bulk", "archive", "fixture", cat_name(i - 1).split()[0].lower()],
            "keywords": ["khi-archive", "test-fixture", f"vol-{i}"],
        })
    return out


def images():
    out = []
    for i in range(1, N + 1):
        kt = KURDISH_TITLES[i % len(KURDISH_TITLES)]
        rt = ROMANIZED_TITLES[i % len(ROMANIZED_TITLES)]
        out.append({
            "projectCode": project_code(i),
            "fileName": f"image_{i:04d}.tiff",
            "volumeName": f"vol-{((i - 1) % 50) + 1:02d}",
            "directory": "/archive/images",
            "pathInExternalVolume": f"/external/khi/images/img_{i:04d}.tiff",
            "autoPath": f"images/{i:04d}",
            "originalTitle": f"{kt} — Plate {i}",
            "alternativeTitle": f"{rt} — Photograph {i}",
            "titleInCentralKurdish": kt,
            "romanizedTitle": rt,
            "subject": [SUBJECTS[i % len(SUBJECTS)], SUBJECTS[(i + 3) % len(SUBJECTS)]],
            "form": "photograph",
            "genre": [GENRES[i % len(GENRES)]],
            "event": EVENTS[i % len(EVENTS)],
            "location": LOCATIONS[i % len(LOCATIONS)],
            "description": f"Archival photograph documenting {SUBJECTS[i % len(SUBJECTS)]} in {LOCATIONS[i % len(LOCATIONS)]}.",
            "personShownInImage": PEOPLE[i % len(PEOPLE)],
            "colorOfImage": [COLORS[i % len(COLORS)]],
            "imageVersion": "MASTER",
            "versionNumber": 1,
            "copyNumber": 1,
            "whereThisImageUsed": ["exhibition", "publication"],
            "fileSize": f"{(i % 50) + 5}MB",
            "extension": "tiff",
            "orientation": "landscape" if i % 2 == 0 else "portrait",
            "dimension": "6000x4000",
            "bitDepth": "16",
            "dpi": "600",
            "manufacturer": EQUIPMENT[i % len(EQUIPMENT)].split()[0],
            "model": EQUIPMENT[i % len(EQUIPMENT)],
            "lens": "50mm f/1.4",
            "creatorArtistPhotographer": PEOPLE[(i + 1) % len(PEOPLE)],
            "contributor": PEOPLE[(i + 2) % len(PEOPLE)],
            "audience": "researchers",
            "accrualMethod": "donation",
            "provenance": f"Archive accession {i:05d}",
            "photostory": f"Photo essay #{i} on {SUBJECTS[i % len(SUBJECTS)]}.",
            "imageStatus": "catalogued",
            "archiveCataloging": "ISAD(G) compliant",
            "physicalAvailability": True,
            "physicalLabel": f"BOX-{(i % 100) + 1:03d}/SHELF-{(i % 20) + 1:02d}",
            "locationInArchiveRoom": f"Room A, Box {(i % 100) + 1:03d}",
            "lccClassification": "DS59.K86",
            "note": "fixture-generated",
            "tags": ["fixture", "bulk", LOCATIONS[i % len(LOCATIONS)].lower()],
            "keywords": ["photograph", "archival", SUBJECTS[i % len(SUBJECTS)].lower()],
            "copyright": "KHI Archive",
            "rightOwner": "Kurdistan Heritage Institute",
            "licenseType": "CC-BY-NC",
            "usageRights": "research-only",
            "availability": "online",
            "owner": "KHI",
            "publisher": PUBLISHERS[i % len(PUBLISHERS)],
            "imageFileUrl": f"https://fixture.khi.local/images/img_{i:04d}.tiff",
        })
    return out


def texts():
    out = []
    for i in range(1, N + 1):
        kt = KURDISH_TITLES[i % len(KURDISH_TITLES)]
        rt = ROMANIZED_TITLES[i % len(ROMANIZED_TITLES)]
        out.append({
            "projectCode": project_code(i),
            "fileName": f"text_{i:04d}.pdf",
            "volumeName": f"vol-{((i - 1) % 50) + 1:02d}",
            "directory": "/archive/texts",
            "pathInExternalVolume": f"/external/khi/texts/txt_{i:04d}.pdf",
            "autoPath": f"texts/{i:04d}",
            "originalTitle": f"{kt} — Volume {i}",
            "alternativeTitle": f"{rt} — Volume {i}",
            "titleInCentralKurdish": kt,
            "romanizedTitle": rt,
            "subject": [SUBJECTS[i % len(SUBJECTS)], SUBJECTS[(i + 2) % len(SUBJECTS)]],
            "genre": [GENRES[i % len(GENRES)]],
            "documentType": "manuscript" if i % 3 == 0 else "book",
            "description": f"Archival text on {SUBJECTS[i % len(SUBJECTS)]} from {LOCATIONS[i % len(LOCATIONS)]}.",
            "script": "Kurdish-Arabic" if i % 2 == 0 else "Latin",
            "transcription": f"Transcribed excerpt #{i}: {rt} discusses {SUBJECTS[i % len(SUBJECTS)].lower()}.",
            "isbn": f"978-3-16-{(148000 + i):06d}-0",
            "assignmentNumber": f"AN-{i:05d}",
            "edition": f"{((i % 5) + 1)}",
            "volume": f"{((i % 10) + 1)}",
            "series": f"KHI Series {((i % 7) + 1)}",
            "textVersion": "MASTER",
            "versionNumber": 1,
            "copyNumber": 1,
            "fileSize": f"{(i % 30) + 1}MB",
            "extension": "pdf",
            "orientation": "portrait",
            "pageCount": (i % 500) + 50,
            "size": "A4",
            "physicalDimensions": "21x29.7cm",
            "language": LANGUAGES[i % len(LANGUAGES)],
            "dialect": DIALECTS[i % len(DIALECTS)],
            "author": PEOPLE[i % len(PEOPLE)],
            "contributors": ", ".join([PEOPLE[(i + 1) % len(PEOPLE)], PEOPLE[(i + 2) % len(PEOPLE)]]),
            "printingHouse": PUBLISHERS[i % len(PUBLISHERS)],
            "audience": "scholars",
            "accrualMethod": "purchase",
            "provenance": f"Acquired from estate of {PEOPLE[i % len(PEOPLE)]}",
            "textStatus": "published",
            "archiveCataloging": "ISAD(G)",
            "physicalAvailability": True,
            "physicalLabel": f"SHELF-{(i % 30) + 1:02d}",
            "locationInArchiveRoom": f"Room B, Shelf {(i % 30) + 1:02d}",
            "lccClassification": "PK6907",
            "note": "fixture-generated",
            "tags": ["fixture", DIALECTS[i % len(DIALECTS)].lower()],
            "keywords": ["text", LANGUAGES[i % len(LANGUAGES)].lower(), GENRES[i % len(GENRES)].lower()],
            "copyright": "KHI Archive",
            "rightOwner": "Kurdistan Heritage Institute",
            "licenseType": "CC-BY-NC",
            "usageRights": "research-only",
            "availability": "online",
            "owner": "KHI",
            "publisher": PUBLISHERS[i % len(PUBLISHERS)],
            "textFileUrl": f"https://fixture.khi.local/texts/txt_{i:04d}.pdf",
        })
    return out


def videos():
    out = []
    for i in range(1, N + 1):
        kt = KURDISH_TITLES[i % len(KURDISH_TITLES)]
        rt = ROMANIZED_TITLES[i % len(ROMANIZED_TITLES)]
        out.append({
            "projectCode": project_code(i),
            "fileName": f"video_{i:04d}.mov",
            "volumeName": f"vol-{((i - 1) % 50) + 1:02d}",
            "directory": "/archive/videos",
            "pathInExternalVolume": f"/external/khi/videos/vid_{i:04d}.mov",
            "autoPath": f"videos/{i:04d}",
            "originalTitle": f"{kt} — Episode {i}",
            "alternativeTitle": f"{rt} — Episode {i}",
            "titleInCentralKurdish": kt,
            "romanizedTitle": rt,
            "subject": [SUBJECTS[i % len(SUBJECTS)]],
            "genre": [GENRES[i % len(GENRES)]],
            "event": EVENTS[i % len(EVENTS)],
            "location": LOCATIONS[i % len(LOCATIONS)],
            "description": f"Archival film footage of {EVENTS[i % len(EVENTS)]} in {LOCATIONS[i % len(LOCATIONS)]}.",
            "personShownInVideo": PEOPLE[i % len(PEOPLE)],
            "colorOfVideo": [COLORS[i % len(COLORS)]],
            "videoVersion": "MASTER",
            "versionNumber": 1,
            "copyNumber": 1,
            "whereThisVideoUsed": ["broadcast", "festival"],
            "fileSize": f"{(i % 200) + 100}MB",
            "extension": "mov",
            "orientation": "landscape",
            "dimension": "3840x2160",
            "resolution": "4K",
            "duration": f"{(i % 60) + 1}:{(i % 60):02d}",
            "bitDepth": "10",
            "frameRate": "24fps",
            "overallBitRate": "100Mbps",
            "videoCodec": "ProRes 422",
            "audioCodec": "PCM",
            "audioChannels": "stereo",
            "language": LANGUAGES[i % len(LANGUAGES)],
            "dialect": DIALECTS[i % len(DIALECTS)],
            "subtitle": "Kurdish, English",
            "creatorArtistDirector": PEOPLE[(i + 1) % len(PEOPLE)],
            "producer": PEOPLE[(i + 3) % len(PEOPLE)],
            "contributor": PEOPLE[(i + 2) % len(PEOPLE)],
            "audience": "general",
            "accrualMethod": "donation",
            "provenance": f"Filmed by {PEOPLE[(i + 1) % len(PEOPLE)]}",
            "videoStatus": "archived",
            "archiveCataloging": "ISAD(G)",
            "physicalAvailability": True,
            "physicalLabel": f"TAPE-{i:05d}",
            "locationInArchiveRoom": f"Vault C, Slot {(i % 100) + 1:03d}",
            "lccClassification": "PN1995",
            "note": "fixture-generated",
            "tags": ["fixture", LOCATIONS[i % len(LOCATIONS)].lower(), GENRES[i % len(GENRES)].lower()],
            "keywords": ["video", "footage", EVENTS[i % len(EVENTS)].lower()],
            "copyright": "KHI Archive",
            "rightOwner": "Kurdistan Heritage Institute",
            "licenseType": "CC-BY-NC",
            "usageRights": "research-only",
            "availability": "online",
            "owner": "KHI",
            "publisher": PUBLISHERS[i % len(PUBLISHERS)],
            "videoFileUrl": f"https://fixture.khi.local/videos/vid_{i:04d}.mov",
        })
    return out


def audios():
    out = []
    for i in range(1, N + 1):
        kt = KURDISH_TITLES[i % len(KURDISH_TITLES)]
        rt = ROMANIZED_TITLES[i % len(ROMANIZED_TITLES)]
        out.append({
            "projectCode": project_code(i),
            "fullName": f"audio_{i:04d}.wav",
            "volumeName": f"vol-{((i - 1) % 50) + 1:02d}",
            "directoryName": "/archive/audios",
            "pathInExternal": f"/external/khi/audios/aud_{i:04d}.wav",
            "autoPath": f"audios/{i:04d}",
            "originTitle": f"{kt} — Track {i}",
            "alterTitle": f"{rt} — Track {i}",
            "centralKurdishTitle": kt,
            "romanizedTitle": rt,
            "form": "song" if i % 2 == 0 else "speech",
            "typeOfBasta": "Halparke" if i % 3 == 0 else None,
            "typeOfMaqam": "Maqam Kurdi" if i % 5 == 0 else None,
            "genre": [GENRES[i % len(GENRES)]],
            "abstractText": f"Audio recording #{i} of {SUBJECTS[i % len(SUBJECTS)]}.",
            "description": f"Field recording in {LOCATIONS[i % len(LOCATIONS)]}, {DIALECTS[i % len(DIALECTS)]} dialect.",
            "speaker": PEOPLE[i % len(PEOPLE)],
            "producer": PEOPLE[(i + 3) % len(PEOPLE)],
            "composer": PEOPLE[(i + 4) % len(PEOPLE)],
            "contributors": [PEOPLE[(i + 1) % len(PEOPLE)], PEOPLE[(i + 2) % len(PEOPLE)]],
            "language": LANGUAGES[i % len(LANGUAGES)],
            "dialect": DIALECTS[i % len(DIALECTS)],
            "typeOfComposition": "Folk",
            "typeOfPerformance": "Solo" if i % 2 == 0 else "Ensemble",
            "lyrics": f"Excerpt of lyrics from track {i}: '{rt} {SUBJECTS[i % len(SUBJECTS)].lower()}'",
            "poet": PEOPLE[(i + 5) % len(PEOPLE)],
            "recordingVenue": f"Studio {(i % 20) + 1}",
            "city": LOCATIONS[i % len(LOCATIONS)],
            "region": "Kurdistan",
            "audience": "public",
            "tags": ["fixture", DIALECTS[i % len(DIALECTS)].lower()],
            "keywords": ["audio", "recording", GENRES[i % len(GENRES)].lower()],
            "physicalAvailability": True,
            "physicalLabel": f"REEL-{i:05d}",
            "locationArchive": f"Vault D, Reel {i:05d}",
            "degitizedBy": "KHI Digital Lab",
            "degitizationEquipment": "Studer A810 + Apogee AD-16X",
            "audioFileNote": "fixture-generated",
            "audioChannel": "stereo",
            "fileExtension": "wav",
            "fileSize": f"{(i % 80) + 20}MB",
            "bitRate": "1411kbps",
            "bitDepth": "24",
            "sampleRate": "96000",
            "audioQualityOutOf10": (i % 10) + 1,
            "audioVersion": "MASTER",
            "versionNumber": 1,
            "copyNumber": 1,
            "lccClassification": "ML3758.K87",
            "accrualMethod": "donation",
            "provenance": f"Recorded by {PEOPLE[(i + 1) % len(PEOPLE)]}",
            "copyright": "KHI Archive",
            "rightOwner": "Kurdistan Heritage Institute",
            "availability": "online",
            "licenseType": "CC-BY-NC",
            "usageRights": "research-only",
            "owner": "KHI",
            "publisher": PUBLISHERS[i % len(PUBLISHERS)],
            "archiveLocalNote": "fixture-generated",
            "audioFileUrl": f"https://fixture.khi.local/audios/aud_{i:04d}.wav",
        })
    return out


def write(name, data):
    path = os.path.join(ROOT, name)
    with open(path, "w") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"  wrote {path}  ({len(data)} records)")


if __name__ == "__main__":
    print("Generating fixtures...")
    write("test-projects-1000.json", projects())
    write("test-images-1000.json", images())
    write("test-texts-1000.json", texts())
    write("test-videos-1000.json", videos())
    write("test-audios-1000.json", audios())
    print("Done.")
