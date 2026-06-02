#!/usr/bin/env python3
"""
KHI Archive Platform — seed-data generator.

Produces 7 JSON files in this directory, each containing 500 records that
match the public Guest*DTO shapes:

    categories.json   ──  GuestCategoryDTO
    persons.json      ──  GuestPersonDTO
    projects.json     ──  GuestProjectDTO   (references persons/categories)
    audios.json       ──  GuestAudioDTO     (references project + person + cats)
    videos.json       ──  GuestVideoDTO     (same)
    texts.json        ──  GuestTextDTO      (same)
    images.json       ──  GuestImageDTO     (same)

The data is deterministic (random.seed(42)) so successive runs give the same
output. Theme: Kurdish heritage archive — names, places, music forms, scripts,
dialects are all real domain vocabulary.
"""

import json
import random
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

random.seed(42)

OUT_DIR = Path(__file__).parent
N = 500
S3_BASE = "https://khi-archive-platform.s3.us-east-1.amazonaws.com/khi-archive-platform-folders"
S3_PERSON = f"{S3_BASE}/persons"
WIKI_CACHE_FILE = OUT_DIR / "wiki_cache.json"

# Real, free, deterministic image CDN — fallback when a real Wikipedia portrait
# can't be resolved. Lorem Picsum serves Unsplash photos.
PICSUM = "https://picsum.photos"

def portrait_url(seed):
    return f"{PICSUM}/seed/{seed}/512/512"

def photo_url(seed, w=1200, h=800):
    return f"{PICSUM}/seed/{seed}/{w}/{h}"


# ─── Real Kurdish historical figures (Wikipedia-backed) ──────────────────────
#
# Each entry pairs a Wikipedia article slug with curated biographical metadata.
# The slug is looked up in wiki_cache.json (populated by probe_wiki.py) to get
# the real portrait URL. If the cache is missing or the slug isn't there, we
# fall back to a deterministic picsum portrait so the file still generates.
#
# Spans musicians (Hesen Zîrek, Mamle, Şivan Perwer, Ayşe Şan, Aram Tigran…),
# poets (Ehmedê Xanî, Sherko Bekas, Cigerxwîn, Goran, Hêmin, Hejar, Piremerd,
# Mawlawi…), political/cultural leaders (Mustafa & Masoud Barzani, Talabani,
# Qazi Muhammad, Ghassemlou, Sheikh Mahmud, Sheikh Said, Said Nursi…),
# writers/scholars (Bachtyar Ali, Mehmed Uzun, Celadet Bedirxan…), and modern
# political/musical figures (Selahattin Demirtaş, Leyla Zana, Aynur Doğan,
# Yıldız Tilbe, Ferhat Tunç…).

KURDISH_FIGURES = [
    # ─── Musicians ───
    {"slug": "Hassan_Zirak", "name": "Hesen Zîrek", "english": "Hassan Zirak",
     "types": ["MUSICIAN"], "gender": "MALE", "birth": 1921, "death": 1972,
     "place_of_birth": "Bukan", "place_of_death": "Bukan", "region": "Mukriyan",
     "bio": "Iconic Kurdish singer-songwriter from Mukriyan, famed for folk and classical recordings."},
    {"slug": "Mohammad_Mamle", "name": "Mihemed Mamle", "english": "Mohammad Mamle",
     "types": ["MUSICIAN"], "gender": "MALE", "birth": 1925, "death": 1999,
     "place_of_birth": "Mahabad", "place_of_death": "Mahabad", "region": "Mukriyan",
     "bio": "Legendary Kurdish vocalist who recorded for Radio Baghdad and Radio Mahabad; central figure of mid-20th-century Mukriyani song."},
    {"slug": "Şivan_Perwer", "name": "Şivan Perwer", "english": "Sivan Perwer",
     "types": ["MUSICIAN", "ACTIVIST"], "gender": "MALE", "birth": 1955, "death": None,
     "place_of_birth": "Wêranşar", "region": "Cizîr",
     "bio": "Kurdish singer, composer, saz player and activist; major voice of Kurdish exile music."},
    {"slug": "Ciwan_Haco", "name": "Ciwan Haco", "english": "Ciwan Haco",
     "types": ["MUSICIAN"], "gender": "MALE", "birth": 1957, "death": None,
     "place_of_birth": "Qamishli", "region": "Rojava",
     "bio": "Kurdish singer-songwriter from Rojava; pioneer of modern Kurdish music."},
    {"slug": "Aram_Tigran", "name": "Aram Dîkran", "english": "Aram Tigran",
     "types": ["MUSICIAN"], "gender": "MALE", "birth": 1934, "death": 2009,
     "place_of_birth": "Qamishli", "place_of_death": "Athens", "region": "Rojava",
     "bio": "Armenian-Kurdish singer celebrated for performing Kurdish folk songs across multiple languages."},
    {"slug": "Ahmet_Kaya", "name": "Ahmet Kaya", "english": "Ahmet Kaya",
     "types": ["MUSICIAN"], "gender": "MALE", "birth": 1957, "death": 2000,
     "place_of_birth": "Malatya", "place_of_death": "Paris", "region": "Serhed",
     "bio": "Kurdish-Turkish protest singer-songwriter; one of the best-known voices of his generation."},
    {"slug": "Ayşe_Şan", "name": "Ayşe Şan", "english": "Ayse Şan",
     "types": ["MUSICIAN"], "gender": "FEMALE", "birth": 1938, "death": 1996,
     "place_of_birth": "Diyarbakir", "place_of_death": "İzmir", "region": "Botan",
     "bio": "Pioneering Kurdish female vocalist who broke convention to perform and record Kurmanji songs publicly."},
    {"slug": "Tahsin_Taha", "name": "Tehsîn Taha", "english": "Tahsin Taha",
     "types": ["MUSICIAN"], "gender": "MALE", "birth": 1939, "death": 1995,
     "place_of_birth": "Akre", "place_of_death": "Hewlêr", "region": "Behdînan",
     "bio": "Beloved Bahdini singer; recorded extensively for Radio Baghdad's Kurdish service."},
    {"slug": "Mazhar_Khaleqi", "name": "Mazher Xaliqî", "english": "Mazhar Khaleqi",
     "types": ["MUSICIAN"], "gender": "MALE", "birth": 1938, "death": None,
     "place_of_birth": "Sanandaj", "region": "Kurdistan Province",
     "bio": "Classical Kurdish vocalist from Sanandaj; founder of the Centre for Kurdish Arts."},
    {"slug": "Adnan_Karim", "name": "Adnan Kerîm", "english": "Adnan Karim",
     "types": ["MUSICIAN"], "gender": "MALE", "birth": 1958, "death": None,
     "place_of_birth": "Slêmanî", "region": "Slêmanî Governorate",
     "bio": "Sorani singer-songwriter known for romantic and patriotic ballads."},
    {"slug": "Mihemed_Şêxo", "name": "Mihemed Şêxo", "english": "Mihemed Şêxo",
     "types": ["MUSICIAN"], "gender": "MALE", "birth": 1948, "death": 1989,
     "place_of_birth": "Qamishli", "place_of_death": "Qamishli", "region": "Rojava",
     "bio": "Kurmanji singer and saz player; major figure of Rojavan music."},
    {"slug": "Aziz_Waisi", "name": "Ezîz Weysî", "english": "Aziz Waisi",
     "types": ["MUSICIAN"], "gender": "MALE", "birth": 1957, "death": None,
     "place_of_birth": "Slêmanî", "region": "Slêmanî Governorate",
     "bio": "Sorani vocalist with a long career on Kurdistan radio and television."},
    {"slug": "Aynur_Doğan", "name": "Aynur Doğan", "english": "Aynur Doğan",
     "types": ["MUSICIAN"], "gender": "FEMALE", "birth": 1975, "death": None,
     "place_of_birth": "Çemişgezek", "region": "Dêrsîm",
     "bio": "Internationally acclaimed Kurdish singer from Dersim; collaborates across folk and world-music traditions."},
    {"slug": "Yıldız_Tilbe", "name": "Yıldız Tilbe", "english": "Yıldız Tilbe",
     "types": ["MUSICIAN"], "gender": "FEMALE", "birth": 1966, "death": None,
     "place_of_birth": "İzmir", "region": "Diaspora",
     "bio": "Kurdish-Turkish singer-songwriter, one of the most popular voices in modern Anatolian pop."},
    {"slug": "Rojda", "name": "Rojda", "english": "Rojda",
     "types": ["MUSICIAN"], "gender": "FEMALE", "birth": 1976, "death": None,
     "place_of_birth": "Mardîn", "region": "Botan",
     "bio": "Kurmanji singer known for her work on Kurdish-language television and at Newroz festivals."},
    {"slug": "Diyar_Dersim", "name": "Diyar Dersim", "english": "Diyar Dersim",
     "types": ["MUSICIAN"], "gender": "MALE", "birth": 1976, "death": None,
     "place_of_birth": "Dêrsîm", "region": "Dêrsîm",
     "bio": "Kurdish singer-songwriter rooted in Dersim's Zazaki/Kurmanji traditions."},
    {"slug": "Ferhat_Tunç", "name": "Ferhat Tunç", "english": "Ferhat Tunç",
     "types": ["MUSICIAN", "ACTIVIST"], "gender": "MALE", "birth": 1964, "death": None,
     "place_of_birth": "Dêrsîm", "region": "Dêrsîm",
     "bio": "Alevi Kurdish singer and activist whose protest songs led to multiple prosecutions."},

    # ─── Poets / Writers ───
    {"slug": "Ehmedê_Xanî", "name": "Ehmedê Xanî", "english": "Ahmad Khani",
     "types": ["POET", "SCHOLAR"], "gender": "MALE", "birth": 1651, "death": 1707,
     "place_of_birth": "Hakkari", "place_of_death": "Bazid", "region": "Botan",
     "bio": "Author of Mem û Zîn, foundational Kurdish epic; a key figure of classical Kurmanji literature."},
    {"slug": "Sherko_Bekas", "name": "Şêrko Bêkes", "english": "Sherko Bekas",
     "types": ["POET"], "gender": "MALE", "birth": 1940, "death": 2013,
     "place_of_birth": "Slêmanî", "place_of_death": "Stockholm", "region": "Slêmanî Governorate",
     "bio": "Foremost modern Sorani poet; founder of the Roanga (\"transparent\") movement."},
    {"slug": "Cigerxwîn", "name": "Cegerxwîn", "english": "Cigerxwîn",
     "types": ["POET", "ACTIVIST"], "gender": "MALE", "birth": 1903, "death": 1984,
     "place_of_birth": "Hesar", "place_of_death": "Stockholm", "region": "Cizîr",
     "bio": "Towering Kurmanji poet of the 20th century; eight published divans of political and lyrical verse."},
    {"slug": "Abdulla_Goran", "name": "Ebdulla Goran", "english": "Abdullah Goran",
     "types": ["POET"], "gender": "MALE", "birth": 1904, "death": 1962,
     "place_of_birth": "Halabja", "place_of_death": "Slêmanî", "region": "Halabja Governorate",
     "bio": "Modernist Sorani poet who reformed Kurdish prosody and pioneered free verse."},
    {"slug": "Piremerd", "name": "Pîremêrd", "english": "Piramerd",
     "types": ["POET", "JOURNALIST"], "gender": "MALE", "birth": 1867, "death": 1950,
     "place_of_birth": "Slêmanî", "place_of_death": "Slêmanî", "region": "Slêmanî Governorate",
     "bio": "Editor of Jiyan newspaper and major Sorani poet of the early 20th century."},
    {"slug": "Hemin_Mukriyani", "name": "Hêmin Mukriyanî", "english": "Hemin Mukriyani",
     "types": ["POET"], "gender": "MALE", "birth": 1921, "death": 1986,
     "place_of_birth": "Lajan", "place_of_death": "Urmia", "region": "Mukriyan",
     "bio": "Mukriyani Sorani poet; key bridge between classical and modern Kurdish verse."},
    {"slug": "Abdurrahman_Sharafkandi", "name": "Hejar Mukriyanî", "english": "Hejar (Abdurrahman Sharafkandi)",
     "types": ["POET", "TRANSLATOR", "LINGUIST"], "gender": "MALE", "birth": 1920, "death": 1991,
     "place_of_birth": "Mahabad", "place_of_death": "Karaj", "region": "Mukriyan",
     "bio": "Poet, translator and lexicographer; author of the seminal Hanbana Borîna dictionary."},
    {"slug": "Mawlawi_Tawagozi", "name": "Mewlewî Tawegozî", "english": "Mawlawi Tawagozi",
     "types": ["POET", "RELIGIOUS_FIGURE"], "gender": "MALE", "birth": 1806, "death": 1882,
     "place_of_birth": "Tawagoz", "region": "Hawraman",
     "bio": "Classical Hawrami/Sorani poet; sheikh of the Qadiriyya order in Hawraman."},
    {"slug": "Abdulla_Pashew", "name": "Ebdulla Peşêw", "english": "Abdulla Pashew",
     "types": ["POET"], "gender": "MALE", "birth": 1946, "death": None,
     "place_of_birth": "Hewlêr", "region": "Hewlêr Governorate",
     "bio": "Major living Sorani poet, long-resident in Finland; widely translated."},
    {"slug": "Latif_Halmat", "name": "Letîf Helmet", "english": "Latif Halmat",
     "types": ["POET"], "gender": "MALE", "birth": 1947, "death": None,
     "place_of_birth": "Kifri", "region": "Garmiyan",
     "bio": "Sorani poet associated with the post-Goran modernist generation."},
    {"slug": "Bachtyar_Ali", "name": "Bextiyar Elî", "english": "Bachtyar Ali",
     "types": ["WRITER", "POET"], "gender": "MALE", "birth": 1966, "death": None,
     "place_of_birth": "Slêmanî", "region": "Slêmanî Governorate",
     "bio": "Award-winning Kurdish novelist (\"I Stared at the Night of the City\")."},
    {"slug": "Mehmed_Uzun", "name": "Mehmed Uzun", "english": "Mehmed Uzun",
     "types": ["WRITER"], "gender": "MALE", "birth": 1953, "death": 2007,
     "place_of_birth": "Siverek", "place_of_death": "Diyarbakir", "region": "Serhed",
     "bio": "Father of the modern Kurmanji novel; wrote in exile in Sweden."},
    {"slug": "Yashar_Kemal", "name": "Yaşar Kemal", "english": "Yaşar Kemal",
     "types": ["WRITER"], "gender": "MALE", "birth": 1923, "death": 2015,
     "place_of_birth": "Hemite", "place_of_death": "Istanbul", "region": "Cilicia",
     "bio": "Internationally renowned Kurdish-Turkish novelist; multiple Nobel nominations for \"Memed, My Hawk\"."},
    {"slug": "Hemin", "name": "Hêmin Mukriyanî", "english": "Hemin",
     "types": ["POET"], "gender": "MALE", "birth": 1921, "death": 1986,
     "place_of_birth": "Lajan", "region": "Mukriyan",
     "bio": "Sorani poet of the Mukriyani school; close associate of Hejar Mukriyani."},
    {"slug": "Ehmedê_Xasî", "name": "Ehmedê Xasî", "english": "Ehmedê Xasî",
     "types": ["POET", "RELIGIOUS_FIGURE"], "gender": "MALE", "birth": 1867, "death": 1951,
     "place_of_birth": "Lice", "region": "Botan",
     "bio": "Zazaki religious poet; author of the first printed Mewlûd in Zazaki."},

    # ─── Politicians / Leaders ───
    {"slug": "Mustafa_Barzani", "name": "Mela Mistefa Barzanî", "english": "Mustafa Barzani",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1903, "death": 1979,
     "place_of_birth": "Barzan", "place_of_death": "Washington, D.C.", "region": "Behdînan",
     "bio": "Founder of the KDP and central leader of the 20th-century Kurdish national movement."},
    {"slug": "Masoud_Barzani", "name": "Mesûd Barzanî", "english": "Masoud Barzani",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1946, "death": None,
     "place_of_birth": "Mahabad", "region": "Hewlêr Governorate",
     "bio": "Former President of the Kurdistan Region of Iraq and longtime leader of the KDP."},
    {"slug": "Jalal_Talabani", "name": "Celal Talebanî", "english": "Jalal Talabani",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1933, "death": 2017,
     "place_of_birth": "Kelkan", "place_of_death": "Berlin", "region": "Slêmanî Governorate",
     "bio": "Founder of the PUK and the first non-Arab President of Iraq."},
    {"slug": "Qazi_Muhammad", "name": "Qazî Mihemed", "english": "Qazi Muhammad",
     "types": ["POLITICIAN", "RELIGIOUS_FIGURE"], "gender": "MALE", "birth": 1893, "death": 1947,
     "place_of_birth": "Mahabad", "place_of_death": "Mahabad", "region": "Mukriyan",
     "bio": "President of the short-lived Republic of Mahabad (1946); executed by the Iranian state."},
    {"slug": "Mahmud_Barzanji", "name": "Şêx Mehmûd Berzencî", "english": "Sheikh Mahmud Barzanji",
     "types": ["POLITICIAN", "RELIGIOUS_FIGURE"], "gender": "MALE", "birth": 1878, "death": 1956,
     "place_of_birth": "Slêmanî", "place_of_death": "Baghdad", "region": "Slêmanî Governorate",
     "bio": "Self-declared King of Kurdistan during the 1920s revolts against the British Mandate."},
    {"slug": "Nechirvan_Barzani", "name": "Nêçîrvan Barzanî", "english": "Nechirvan Barzani",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1966, "death": None,
     "place_of_birth": "Barzan", "region": "Hewlêr Governorate",
     "bio": "Current President of the Kurdistan Region of Iraq."},
    {"slug": "Sheikh_Ubeydullah", "name": "Şêx Ubeydullahê Nehrî", "english": "Sheikh Ubeydullah",
     "types": ["RELIGIOUS_FIGURE", "POLITICIAN"], "gender": "MALE", "birth": 1826, "death": 1883,
     "place_of_birth": "Şemzînan", "place_of_death": "Mecca", "region": "Behdînan",
     "bio": "Naqshbandi sheikh who led the first major modern Kurdish nationalist revolt (1880)."},
    {"slug": "Sheikh_Said", "name": "Şêx Seîdê Pîranî", "english": "Sheikh Said",
     "types": ["RELIGIOUS_FIGURE", "POLITICIAN"], "gender": "MALE", "birth": 1865, "death": 1925,
     "place_of_birth": "Hınıs", "place_of_death": "Diyarbakir", "region": "Serhed",
     "bio": "Leader of the 1925 Kurdish revolt against the early Turkish Republic."},
    {"slug": "Sadegh_Sharafkandi", "name": "Sadiq Şerefkendî", "english": "Sadegh Sharafkandi",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1938, "death": 1992,
     "place_of_birth": "Bukan", "place_of_death": "Berlin", "region": "Mukriyan",
     "bio": "Secretary-General of the PDKI; assassinated at the Mykonos restaurant in 1992."},
    {"slug": "Idris_Barzani", "name": "Idrîs Barzanî", "english": "Idris Barzani",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1944, "death": 1987,
     "place_of_birth": "Barzan", "place_of_death": "Tehran", "region": "Hewlêr Governorate",
     "bio": "KDP leader, son of Mustafa Barzani and elder brother of Masoud."},
    {"slug": "Ibrahim_Ahmad", "name": "Îbrahîm Ehmed", "english": "Ibrahim Ahmad",
     "types": ["POLITICIAN", "WRITER"], "gender": "MALE", "birth": 1914, "death": 2000,
     "place_of_birth": "Slêmanî", "place_of_death": "London", "region": "Slêmanî Governorate",
     "bio": "Sorani novelist, lawyer, and senior figure of the early KDP."},
    {"slug": "Salahaddin_Bahaaddin", "name": "Selahedîn Bahadîn", "english": "Salahaddin Bahaaddin",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1953, "death": None,
     "place_of_birth": "Hewlêr", "region": "Hewlêr Governorate",
     "bio": "Secretary-General of the Kurdistan Islamic Union."},
    {"slug": "Yılmaz_Güney", "name": "Yılmaz Güney", "english": "Yılmaz Güney",
     "types": ["FILMMAKER", "WRITER", "ACTIVIST"], "gender": "MALE", "birth": 1937, "death": 1984,
     "place_of_birth": "Adana", "place_of_death": "Paris", "region": "Cilicia",
     "bio": "Kurdish filmmaker and Cannes Palme d'Or winner for Yol."},
    {"slug": "Selahattin_Demirtaş", "name": "Selahattin Demirtaş", "english": "Selahattin Demirtaş",
     "types": ["POLITICIAN", "ACTIVIST", "WRITER"], "gender": "MALE", "birth": 1973, "death": None,
     "place_of_birth": "Palu", "region": "Serhed",
     "bio": "Co-leader of the HDP; political prisoner since 2016."},
    {"slug": "Leyla_Zana", "name": "Leyla Zana", "english": "Leyla Zana",
     "types": ["POLITICIAN", "ACTIVIST"], "gender": "FEMALE", "birth": 1961, "death": None,
     "place_of_birth": "Silvan", "region": "Botan",
     "bio": "Kurdish parliamentarian and activist; Sakharov Prize laureate."},
    {"slug": "Ahmet_Türk", "name": "Ehmedê Tirk", "english": "Ahmet Türk",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1942, "death": None,
     "place_of_birth": "Mardîn", "region": "Botan",
     "bio": "Veteran Kurdish politician; co-chair of the DTK."},
    {"slug": "Simko_Shikak", "name": "Simko Şikakî", "english": "Simko Shikak",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1887, "death": 1930,
     "place_of_birth": "Çehrîq", "place_of_death": "Salmas", "region": "Şikakî tribe",
     "bio": "Kurdish chieftain who led the Simko revolt against the Iranian state."},
    {"slug": "Ihsan_Nuri", "name": "Îhsan Nûrî Paşa", "english": "Ihsan Nuri",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1893, "death": 1976,
     "place_of_birth": "Bitlis", "place_of_death": "Tehran", "region": "Botan",
     "bio": "Commander of the Republic of Ararat (1927–1930)."},
    {"slug": "Ferzende_Beg", "name": "Ferzende Beg", "english": "Ferzende",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1875, "death": 1937,
     "place_of_birth": "Mûş", "region": "Serhed",
     "bio": "Early-20th-century Kurdish rebel commander."},
    {"slug": "Foad_Mostafa_Soltani", "name": "Foad Mistefa Sultanî", "english": "Foad Mostafa Soltani",
     "types": ["POLITICIAN", "ACTIVIST"], "gender": "MALE", "birth": 1948, "death": 1979,
     "place_of_birth": "Mariwan", "place_of_death": "Mariwan", "region": "Kurdistan Province",
     "bio": "Founding figure of Komala; killed shortly after the Iranian revolution."},
    {"slug": "Abdullah_Çatlı", "name": "Ebdulla Çatlı", "english": "Abdullah Çatlı",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1956, "death": 1996,
     "place_of_birth": "Nevşehir", "place_of_death": "Susurluk", "region": "Diaspora",
     "bio": "Turkish ultra-nationalist of Kurdish background; central figure of the Susurluk scandal."},
    {"slug": "Refik_Saydam", "name": "Refîq Seydam", "english": "Refik Saydam",
     "types": ["POLITICIAN"], "gender": "MALE", "birth": 1881, "death": 1942,
     "place_of_birth": "Istanbul", "place_of_death": "Istanbul", "region": "Diaspora",
     "bio": "Fourth Prime Minister of Turkey; Kurdish background."},

    # ─── Religious / Scholars ───
    {"slug": "Said_Nursi", "name": "Bedîuzzeman Saîdê Kurdî", "english": "Said Nursi",
     "types": ["RELIGIOUS_FIGURE", "SCHOLAR", "WRITER"], "gender": "MALE", "birth": 1877, "death": 1960,
     "place_of_birth": "Nurs", "place_of_death": "Şanlıurfa", "region": "Bitlis",
     "bio": "Influential Islamic scholar; author of the Risale-i Nur corpus."},
    {"slug": "Celadet_Bedir_Khan", "name": "Celadet Alî Bedirxan", "english": "Celadet Bedir Khan",
     "types": ["LINGUIST", "WRITER", "POLITICIAN"], "gender": "MALE", "birth": 1893, "death": 1951,
     "place_of_birth": "Istanbul", "place_of_death": "Damascus", "region": "Diaspora",
     "bio": "Codifier of the modern Kurmanji Latin alphabet and editor of the Hawar journal."},
    {"slug": "Cemal_Nebez", "name": "Cemal Nebez", "english": "Jamal Nebez",
     "types": ["LINGUIST", "WRITER"], "gender": "MALE", "birth": 1933, "death": 2018,
     "place_of_birth": "Slêmanî", "place_of_death": "Berlin", "region": "Slêmanî Governorate",
     "bio": "Kurdish linguist, mathematician and language reformer based in West Berlin."},
    {"slug": "Mehrdad_Izady", "name": "Mehrdad Îzady", "english": "Mehrdad Izady",
     "types": ["SCHOLAR", "HISTORIAN"], "gender": "MALE", "birth": 1963, "death": None,
     "place_of_birth": "Tehran", "region": "Diaspora",
     "bio": "Iranian-Kurdish historian and cartographer of the modern Kurdish nation."},
]


def load_wiki_cache():
    if not WIKI_CACHE_FILE.exists():
        return {}
    try:
        return json.loads(WIKI_CACHE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {}


def figure_portrait(slug, cache):
    entry = cache.get(slug)
    if entry and entry.get("image"):
        return entry["image"]
    return portrait_url(slug)

# ─── Domain vocabulary ────────────────────────────────────────────────────────

FIRST_NAMES_M = [
    "Ahmed", "Aram", "Azad", "Baxtiyar", "Bakhtiar", "Bekas", "Cigerxwîn", "Dilshad",
    "Diyar", "Goran", "Hawre", "Hejar", "Hemin", "Hêmin", "Hassan", "Hussein",
    "Karwan", "Kawa", "Kamal", "Mahmoud", "Mehmûd", "Mela", "Nali", "Omer",
    "Piremerd", "Pirêmêrd", "Rebwar", "Rezgar", "Sherko", "Sherzad", "Sirwan",
    "Soran", "Tahir", "Yousif", "Zêrevan", "Zhilwan", "Faqi", "Mahwi", "Qadir",
    "Saman", "Salar", "Salam", "Sami", "Salah", "Shwan", "Shwana", "Sulaiman",
    "Talib", "Wirya", "Xelil", "Xosrew", "Zakir", "Zana", "Mukriyan", "Aras",
    "Berzan", "Borhan", "Cheko", "Dara", "Erfan", "Farhad", "Hardi", "Halmat",
    "Idris", "Jamal", "Karzan", "Kosrat", "Latif", "Loqman", "Mawlawi", "Mihemed",
    "Mihemedi", "Najm", "Nawzad", "Nezir", "Nuh", "Osman", "Peshawa", "Qadr",
    "Qadri", "Qehraman", "Rahman", "Ramin", "Ranj", "Rauf", "Razi", "Renas",
    "Sabah", "Sabir", "Sadiq", "Salahaddin", "Sardar", "Sefin", "Selim", "Serdar",
    "Shahab", "Shahin", "Shamal", "Shexmus", "Sidqi", "Sirwe", "Sîrwan", "Taha",
]

FIRST_NAMES_F = [
    "Avesta", "Berivan", "Bizav", "Beyan", "Berfîn", "Chinar", "Çinar", "Dilara",
    "Dilan", "Dilshad", "Evîn", "Eva", "Farah", "Gulistan", "Hêvî", "Helbest",
    "Hozan", "Jiyan", "Jin", "Kazhal", "Lana", "Lava", "Leyla", "Leyli",
    "Mehri", "Mizgîn", "Nazê", "Nergiz", "Nesrîn", "Niga", "Pari", "Perwîn",
    "Roj", "Rojda", "Rojîn", "Sara", "Saya", "Sêv", "Shahla", "Shilan",
    "Shirin", "Shîrîn", "Sîvan", "Sozdar", "Sîma", "Têlî", "Tara", "Vîyan",
    "Yara", "Zerya", "Zîlan", "Zîn", "Zerya", "Çîçek", "Hejar", "Mizgîn",
    "Awa", "Avîn", "Berke", "Bêrî", "Berfin", "Bahar", "Diyari", "Dilbar",
    "Emel", "Ferîde", "Gulen", "Helîme", "Hîvron", "Iman", "Jale", "Karîn",
    "Kelhûr", "Lêla", "Mela", "Meryem", "Nawal", "Newroza", "Nîgar", "Pakîze",
    "Roza", "Roxan", "Saide", "Shadan", "Shadiya", "Sînem", "Tawus", "Wîjdan",
    "Xezal", "Xemgîn", "Yektî", "Zîlan", "Zîn", "Zêna",
]

SURNAMES = [
    "Bekas", "Khani", "Goran", "Cizîrî", "Hejar", "Hemin", "Piremerd", "Nali",
    "Mahwi", "Cigerxwîn", "Sheikh Rezayi", "Reshid", "Faqi Tayran", "Mihemed Salih",
    "Qadr", "Rasul", "Yusif", "Mihemed", "Mihemedi", "Kerim", "Karim", "Kakei",
    "Rashid", "Hassan", "Talabani", "Barzani", "Sorani", "Ezîdî", "Hewramî",
    "Mukriyanî", "Sheikhi", "Naqshbandi", "Qadiri", "Şirwan", "Sileymanî",
    "Hewlêrî", "Duhokî", "Halabjayî", "Kerkukî", "Mahabadî", "Sanandajî",
    "Aslan", "Demirci", "Yıldız", "Çelik", "Akdoğan", "Türkmen", "Karasu",
    "Şahin", "Doğan", "Kaya", "Arslan", "Korkmaz", "Tekîn", "Ahmadi", "Karimî",
    "Salihî", "Salîh", "Salar", "Sîdîq", "Sabri", "Sabîr", "Bilal", "Bekir",
    "Birûsk", "Çetin", "Diljen", "Diyari", "Dîn", "Ehmedî", "Ferman", "Goyî",
    "Hekarî", "Henarî", "Îsmaîl", "Jiyan", "Kakey", "Kerwan", "Lawkî",
    "Mêrgewerî", "Mecnûn", "Nemir", "Nûrî", "Pîran", "Pîroz", "Qereçolî",
    "Reber", "Rezgar", "Şerefxan", "Şivan", "Şîrnaxî", "Şwana", "Têlî",
    "Wîjdanî", "Xelîfe", "Yeşar", "Zaxoyî", "Zarî", "Zîlanî",
]

CITIES = [
    "هەولێر", "سلێمانی", "دهۆک", "هەڵەبجە", "کەرکوک", "مەهاباد", "سنە",
    "ئامەد", "قامیشلی", "هەورامان", "موکریان", "ئاکرێ", "سۆران", "چۆمان",
    "ڕانیە", "پەنجوێن", "سەید سادق", "دەربەندیخان", "چەمچەماڵ", "کەلار",
    "خانەقین", "شنگال", "زاخۆ", "ئامێدی", "بەردەرەش", "بانە", "بۆکان",
    "سەقز", "مەریوان", "پاوە", "کرماشان", "ئیلام", "لۆریستان", "بیجار",
    "جزیر", "ئەفرین", "کۆبانێ", "دێرک", "حەسەکە", "مێردین", "بەدلیس",
    "وان", "موش", "بنگۆل", "شرنەخ", "وەرانشار", "ڕیها", "دێرسیم",
    "مەرعەش", "ئەدیامان", "بەرلینی کۆچبەری", "ستۆکهۆڵم", "لەندەن",
]

REGIONS = [
    "پارێزگای سلێمانی", "پارێزگای هەولێر", "پارێزگای دهۆک",
    "پارێزگای هەڵەبجە", "پارێزگای کەرکوک", "ڕۆژئاوای ئازەربایجان",
    "پارێزگای کوردستان", "پارێزگای کرماشان", "پارێزگای ئیلام",
    "هەورامان", "موکریان", "بەهدینان", "سۆران", "گەرمیان", "جزیر",
    "بۆتان", "سەرحەد", "دێرسیم", "ڕۆژئاوا", "شارەزوور",
]

MAQAMS = [
    "مەقامی هەورامی", "مەقامی بیاتی", "مەقامی سەبا", "مەقامی حیجاز",
    "مەقامی نەهاوەند", "مەقامی کوردی", "مەقامی حسێنی", "مەقامی ڕاست",
    "مەقامی سیکا", "مەقامی چارگاه", "مەقامی شور", "مەقامی حیجاز کار",
    "مەقامی موکابیل", "مەقامی سەهناز", "مەقامی نەوروز", "مەقامی ماهور",
    "مەقامی عەشیران", "مەقامی بیاتی عەراق",
]

BASTAS = [
    "بەستی مەوال", "بەستی پێشڕەو", "بەستی هەورامی", "بەستی لاوەند",
    "بەستی چەپک", "بەستی هەیران", "بەستی مەخامی", "بەستی گەڕیان",
    "بەستی میسۆپۆتامیا", "بەستی شەهناز", "بەستی سۆزیناک", "بەستی حیجازی",
]

GENRES_MUSIC = [
    "ستران", "لاوک", "هەیران", "دلۆک", "مۆسیقای تەنبوور", "مۆسیقای دەف",
    "ئەفسانە", "ئاوازی سۆفی", "سەما", "مەقام", "بەستی فۆلکلۆر",
    "ستری زەماوەند", "شیوەن", "بەخەواندن", "ستری منداڵان", "ستری بەرگری",
    "پۆپ", "کلاسیک", "فۆلکلۆری نوێ", "کۆڕەسپانە", "بێ ئامێر", "ئامێریی",
    "مەولوود", "دەنگبێژ", "ستران بێژ",
]

GENRES_VIDEO = [
    "دۆکیومێنتاری", "چاوپێکەوتن", "نواندن", "ڕێوڕەسم", "زەماوەند",
    "ئاهەنگ", "نەوروز", "سەمای فۆلکلۆری", "ڕێوڕەسمی ئاینی", "هەواڵنامە",
    "میژووی زارەکی", "تۆمارکردنی بواری", "کۆنسێرت", "کارە کلتوورییەکان",
    "فێرکاری", "وانە", "کۆنفرانس",
]

GENRES_IMAGE = [
    "وێنەی پۆرترێ", "دیمەنی سروشت", "ئەندازیاری بینا", "ئەتنۆگرافی",
    "دۆکیومێنتاری", "ئاهەنگی نەوروز", "زەماوەند", "شوێنی ئاینی",
    "لاپەڕەی دەستنووس", "وێنە لە ئاسمانەوە", "وێنەی میژوویی",
    "وێنەی گرووپی", "شەقام", "جل و بەرگ", "ئامێر", "پۆرترێی ستۆدیۆ",
]

GENRES_TEXT = [
    "شیعر", "نووسراو", "ڕیسالەی ئاینی", "دەستنووس", "تەفسیر",
    "شرۆڤەی حەدیث", "نووسراوی سۆفی", "چیرۆکی فۆلکلۆر", "پەند و وتە",
    "بیرەوەری", "ژیاننامە", "میژوو", "زمانەوانی", "ڕێزمان",
    "فەرهەنگ", "ڕۆژنامە", "گۆڤار", "نامە", "دیکرێ", "ڕەچەڵەک",
]

INSTRUMENTS = [
    "تەنبوور", "دەف", "ساز", "بلیوور", "زورنا", "دەهۆل", "کەمەنچە",
    "نەی", "عوود", "قانوون", "سانتوور", "تار", "تونباک", "سێتار", "چۆگور",
]

LANGUAGES = [
    "کوردی", "عەرەبی", "فارسی", "تورکی", "ئارامی", "ئەرمەنی",
    "ئینگلیزی", "ڕووسی",
]

KURDISH_DIALECTS = [
    "سۆرانی", "کرمانجی", "هەورامی (گۆرانی)", "زازاکی", "کەڵهوڕی",
    "لەکی", "بەهدینی", "موکریانی", "شکاکی", "شێخبزینی",
]

SCRIPTS = ["عەرەبی-کوردی", "لاتینی-کوردی", "کیریلی-کوردی", "عەرەبی", "لاتینی", "تێکەڵ"]

DOCUMENT_TYPES = [
    "دەستنووس", "کتێبی چاپکراو", "پەرتووکچە", "نامە", "دیکرێ",
    "ڕۆژنامە", "گۆڤار", "ڕۆژەوار", "تەزکیرە", "بەڵگەی یاسایی",
    "نووسراوی ئاینی", "کۆکراوەی شیعر",
]

PERSON_TYPES = [
    "MUSICIAN", "POET", "WRITER", "SCHOLAR", "RELIGIOUS_FIGURE", "POLITICIAN",
    "HISTORIAN", "TRANSLATOR", "JOURNALIST", "ARTIST", "PHOTOGRAPHER",
    "FILMMAKER", "DANCER", "LINGUIST", "ACTIVIST", "TEACHER",
]

CATEGORY_BASE = [
    ("MUS", "مۆسیقا", "تۆمارکراوەکانی مۆسیقای کوردی و نۆتاسیۆن"),
    ("POE", "شیعر", "بەرهەمی شیعری کوردی لە زمان و شێوەزارە جیاوازەکاندا"),
    ("ORL", "میژووی زارەکی", "چاوپێکەوتنە تۆمارکراوەکان لەگەڵ پیران و شایەتە میژووییەکان"),
    ("MAN", "دەستنووسەکان", "دەستنووسە کۆنەکانی پێش چاپ بە کوردی، عەرەبی و فارسی"),
    ("PHO", "وێنە", "وێنە لە ستۆدیۆ و کێشراوەکانی بوار"),
    ("FLM", "فیلم", "فیلمی دۆکیومێنتاری و ئەتنۆگرافی"),
    ("FOL", "چیرۆکی فۆلکلۆر", "چیرۆکە فۆلکلۆریە تۆمارکراوەکان و ئەدەبیاتی زارەکی"),
    ("REL", "دەقە ئاینییەکان", "تەفسیر، شرۆڤەی حەدیث، مەولوود و بەرهەمی سۆفی"),
    ("LNG", "زمانەوانی", "ڕێزمان، فەرهەنگ و توێژینەوەی شێوەزار"),
    ("ETH", "ئەتنۆگرافی", "نەریت، جل و بەرگ، خواردن، ڕێوڕەسم"),
    ("INS", "ئامێرە مۆسیقایییەکان", "بەڵگەنامەکردنی ئامێرە سونەتییەکان"),
    ("HIS", "میژوو", "سەرچاوە میژوویی و کرۆنیکیلەکان"),
    ("DAN", "سەما و گۆڤند", "بەڵگەنامەکردنی سەمای فۆلکلۆری کوردی"),
    ("CER", "ڕێوڕەسم", "زەماوەند، نەورۆز، ناشتن و ڕێوڕەسمی ئاینی"),
    ("CRA", "پیشە دەستییەکان", "چنین، کارکردن لە کانزا و چەرم"),
    ("ARC", "ئەندازیاری بینا", "ئەندازیاری بینای گەلێی و ئاینی"),
    ("DIA", "کۆچبەری", "بەرهەمی بەرهەمهێنراو لە کۆمەڵگەی کۆچبەری دەرەوەی وڵات"),
    ("WOM", "دەنگی ژنان", "بەرهەم لەلایەن ژنانی کورد و دەربارەی ئەوان"),
    ("CHL", "منداڵان", "ستران، چیرۆک و فێرکاری بۆ منداڵان"),
    ("LIT", "ئەدەبیات", "ئەدەبیاتی نوێ و کلاسیکی کوردی"),
    ("PRS", "ڕۆژنامەنووسی", "ڕۆژنامە، گۆڤار و نووسراوەکان"),
    ("MAP", "نەخشە", "نەخشە میژوویی و سەردەمیانی"),
    ("LEG", "یاسایی", "بەڵگەنامەی یاسایی، فەتوا و دیکرێ"),
    ("GEN", "ڕەچەڵەک", "تۆمارکردنی نەسەب و دارستانی خێزان"),
    ("RES", "بەرگری", "بەرهەمە پەیوەستەکان بە بزاوتە بەرگرییەکانەوە"),
    ("SUF", "سۆفیزم", "ڕەوتە سۆفییەکان و دەقەکانیان"),
    ("MED", "پزیشکی نەریتی", "پزیشکی فۆلکلۆری و چارەسەرکارە تایبەتییەکان"),
    ("AST", "ئەستێرەناسی", "ئەستێرەناسی گەلێی و رۆژژمێرە سونەتییەکان"),
    ("CUI", "خواردنی نەریتی", "ڕێسایی خواردن و نەریتی خواردن"),
    ("AGR", "جووتیاری", "تەکنیکی کشتوکاڵی و سوڕی وەرزەکان"),
]

LICENSE_TYPES = [
    "CC BY 4.0", "CC BY-SA 4.0", "CC BY-NC 4.0", "CC BY-NC-SA 4.0",
    "CC BY-ND 4.0", "بەربڵاوی گشتی", "هەموو مافەکان پارێزراون",
    "تەنیا بۆ فێرکاری", "تەنیا لە ئەرشیفدا",
]

AVAILABILITY = ["گشتی", "سنووردار", "تاوەکوو دواتر پاشپێکراو", "ناوەخۆیی", "تەنیا ئەرشیف"]

AUDIENCES = [
    "خەڵک", "توێژەران", "منداڵان", "گەورەسالان",
    "کۆمەڵگەی ئاینی", "ئەکادیمی", "کۆچبەری",
]

# ─── Helpers ──────────────────────────────────────────────────────────────────

def iso_now_utc(dt=None):
    dt = dt or datetime.now(timezone.utc)
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")

def random_instant(start_year=1900, end_year=2025):
    start = datetime(start_year, 1, 1, tzinfo=timezone.utc)
    end = datetime(end_year, 12, 31, tzinfo=timezone.utc)
    delta = (end - start).total_seconds()
    return start + timedelta(seconds=random.uniform(0, delta))

def iso(dt):
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z") if dt else None

def maybe(p, value):
    """Return value with probability p, else None."""
    return value if random.random() < p else None

def pick(seq):
    return random.choice(seq)

def sample(seq, lo, hi):
    k = random.randint(lo, min(hi, len(seq)))
    return random.sample(seq, k)

def slugify(s):
    out = []
    for ch in s.upper().replace(" ", "_"):
        if ch.isalnum() or ch == "_":
            out.append(ch)
    return "".join(out)[:24]

def make_central_kurdish_title(en):
    """Cheap CK transliteration stand-in — picks a Sorani-flavored phrase."""
    options = [
        "بەرهەمی میراتی", "گۆرانی فۆلکلۆر", "هۆنراوەی هاوراز",
        "دەنگی هەورامان", "بەرهەمی موکریان", "نامەی ئیبراهیم",
        "کۆمەڵە چیرۆکی فۆلکلۆر", "نووسینەکانی نالی",
        "دیوانی گۆران", "بەرگی شیعرە کۆنەکان", "کلاوەرۆژ",
    ]
    return pick(options)

def romanize(name):
    """Light romanization stand-in (replaces diacritics)."""
    table = {
        "ç": "ch", "Ç": "Ch", "ş": "sh", "Ş": "Sh", "î": "i", "Î": "I",
        "û": "u", "Û": "U", "ê": "e", "Ê": "E", "ı": "i", "İ": "I",
    }
    return "".join(table.get(ch, ch) for ch in name)

# ─── Sorani translation tables ────────────────────────────────────────────────

CITY_LATIN_TO_SORANI = {
    "Slêmanî": "سلێمانی", "Sulaymaniyah": "سلێمانی", "Hewlêr": "هەولێر", "Erbil": "هەولێر",
    "Duhok": "دهۆک", "Halabja": "هەڵەبجە", "Kerkûk": "کەرکوک", "Kirkuk": "کەرکوک",
    "Mahabad": "مەهاباد", "Sanandaj": "سنە", "Diyarbakir": "ئامەد", "Qamishli": "قامیشلی",
    "Bukan": "بۆکان", "Saqqez": "سەقز", "Mariwan": "مەریوان", "Akre": "ئاکرێ",
    "Wêranşar": "وەرانشار", "Bazid": "بایەزید", "Hakkari": "حەکاری", "Çemişgezek": "چەمشگەزەک",
    "Dêrsîm": "دێرسیم", "Mardîn": "مێردین", "Kifri": "کفری", "Lajan": "لاجان",
    "Tawagoz": "تەواگۆز", "Hesar": "حەسار", "Stockholm": "ستۆکهۆڵم", "Karaj": "کەرەج",
    "Urmia": "ورمێ", "Tehran": "تاران", "Berlin": "بەرلین", "Paris": "پاریس",
    "London": "لەندەن", "Washington, D.C.": "واشنگتن", "Damascus": "دیمەشق",
    "Istanbul": "ئیستەنبوڵ", "Baghdad": "بەغدا", "Adana": "ئەدەنە", "Hemite": "هەمیتە",
    "Siverek": "سیوەرەک", "Lice": "لیجە", "Çehrîq": "چەهریق", "Salmas": "سەلماس",
    "Bitlis": "بەدلیس", "Mûş": "موش", "Hınıs": "هنس", "Nurs": "نورس",
    "Şanlıurfa": "ڕیها", "Mecca": "مەککە", "Şemzînan": "شەمزینان",
    "Nevşehir": "نەوشار", "Susurluk": "سوسورلوک", "Barzan": "بارزان", "Kelkan": "کەلکان",
    "Palu": "پالو", "Silvan": "سلیڤان", "İzmir": "ئیزمیر", "Athens": "ئاتێن",
}

REGION_LATIN_TO_SORANI = {
    "Slêmanî Governorate": "پارێزگای سلێمانی",
    "Hewlêr Governorate": "پارێزگای هەولێر",
    "Duhok Governorate": "پارێزگای دهۆک",
    "Halabja Governorate": "پارێزگای هەڵەبجە",
    "Kerkûk Governorate": "پارێزگای کەرکوک",
    "Kurdistan Province": "پارێزگای کوردستان",
    "Mukriyan": "موکریان", "Hawraman": "هەورامان", "Behdînan": "بەهدینان",
    "Soran": "سۆران", "Garmiyan": "گەرمیان", "Cizîr": "جزیر",
    "Botan": "بۆتان", "Serhed": "سەرحەد", "Dêrsîm": "دێرسیم",
    "Rojava": "ڕۆژئاوا", "Diaspora": "کۆچبەری", "Cilicia": "کیلیکیا",
    "Bitlis": "بەدلیس", "Şikakî tribe": "هۆزی شکاکی",
}

PERSON_TYPE_KU = {
    "MUSICIAN": "مۆسیقاژەن", "POET": "شاعیر", "WRITER": "نووسەر",
    "SCHOLAR": "زانا", "RELIGIOUS_FIGURE": "کەسایەتی ئاینی",
    "POLITICIAN": "سیاسەتمەدار", "HISTORIAN": "میژوونووس",
    "TRANSLATOR": "وەرگێر", "JOURNALIST": "ڕۆژنامەنووس",
    "ARTIST": "هونەرمەند", "PHOTOGRAPHER": "وێنەگر",
    "FILMMAKER": "فیلمساز", "DANCER": "سەماکار",
    "LINGUIST": "زمانەوان", "ACTIVIST": "چالاکوان",
    "TEACHER": "مامۆستا",
}

def to_sorani_city(latin):
    if latin is None:
        return None
    return CITY_LATIN_TO_SORANI.get(latin, latin)

def to_sorani_region(latin):
    if latin is None:
        return None
    return REGION_LATIN_TO_SORANI.get(latin, latin)

def types_to_sorani(types):
    return [PERSON_TYPE_KU.get(t, t) for t in (types or [])]


# ─── Generators ───────────────────────────────────────────────────────────────

def gen_categories(n=N):
    """500 categories — the base set repeated/varied with subcodes like MUS_002."""
    out = []
    base_len = len(CATEGORY_BASE)
    for i in range(n):
        base = CATEGORY_BASE[i % base_len]
        code = base[0] if i < base_len else f"{base[0]}_{(i // base_len) + 1:03d}"
        name = base[1] if i < base_len else f"{base[1]} (بەشی {(i // base_len) + 1})"
        desc = base[2]
        keywords = sample(
            ["میراتی کوردی", "کوردستان", "ئەرشیف", "نەریت", "فۆلکلۆر", "زارەکی",
             "میژوویی", "ئەتنۆگرافی", "دیجیتاڵکراو", "سەرچاوەی سەرەکی",
             base[1], "میراتی کلتووری"],
            2, 5,
        )
        created = random_instant(2018, 2025)
        out.append({
            "id": i + 1,
            "categoryCode": code,
            "name": name,
            "description": desc,
            "keywords": keywords,
            "projectCount": random.randint(0, 80),
            "createdAt": iso(created),
        })
    return out

def _life_date(year, precision_pool=("FULL", "MONTH_ONLY", "YEAR_ONLY")):
    """Generate a deterministic-ish date from a year, respecting precision options."""
    if year is None:
        return None, None
    precision = pick(precision_pool)
    if precision == "YEAR_ONLY":
        return date(year, 1, 1).isoformat(), "YEAR_ONLY"
    if precision == "MONTH_ONLY":
        return date(year, random.randint(1, 12), 1).isoformat(), "MONTH_ONLY"
    return date(year, random.randint(1, 12), random.randint(1, 28)).isoformat(), "FULL"


def gen_persons(n=N):
    """
    Output 500 persons, each one a real Kurdish figure (cycled across the
    curated list with version suffixes on the personCode). Every record has a
    real Wikipedia portrait (from wiki_cache.json) or a deterministic picsum
    fallback if a figure isn't cached.
    """
    cache = load_wiki_cache()
    out = []
    used_codes = set()

    for i in range(n):
        fig = KURDISH_FIGURES[i % len(KURDISH_FIGURES)]
        cycle = (i // len(KURDISH_FIGURES)) + 1

        # build a readable, unique personCode. First cycle keeps the bare slug;
        # subsequent cycles append _V2, _V3, ... so we still hit 500 unique codes.
        base_code = slugify(fig["english"].replace(" ", ""))
        code = base_code if cycle == 1 else f"{base_code}_V{cycle}"
        suffix = 2
        while code in used_codes:
            code = f"{base_code}_V{cycle}_{suffix}"
            suffix += 1
        used_codes.add(code)

        dob_iso, dob_prec = _life_date(fig.get("birth"))
        dod_iso, dod_prec = _life_date(fig.get("death")) if fig.get("death") else (None, None)

        place_b_ku = to_sorani_city(fig.get("place_of_birth")) or "کوردستان"
        place_d_ku = to_sorani_city(fig.get("place_of_death"))
        region_ku = to_sorani_region(fig.get("region")) or "کوردستان"
        types_ku = "، ".join(types_to_sorani(fig["types"]))

        desc = (
            f"{fig['name']} ({fig['birth']}–{fig['death'] if fig.get('death') else 'ئێستا'}) — "
            f"{types_ku} لە {place_b_ku}. "
            f"کەسایەتییەکی ناودار لە میراتی کلتووری کوردیدا، "
            f"کاری گرنگی لە بواری {types_ku}دا کردووە."
        )

        out.append({
            "id": i + 1,
            "personCode": code,
            "mediaPortrait": figure_portrait(fig["slug"], cache),
            "fullName": fig["name"],
            "nickname": fig.get("english") if fig.get("english") != fig["name"] else None,
            "romanizedName": fig["english"],
            "gender": fig["gender"],
            "personType": fig["types"],
            "region": region_ku,
            "dateOfBirth": dob_iso,
            "dateOfBirthPrecision": dob_prec,
            "placeOfBirth": place_b_ku,
            "dateOfDeath": dod_iso,
            "dateOfDeathPrecision": dod_prec,
            "placeOfDeath": place_d_ku,
            "description": desc,
            "projectCount": random.randint(0, 12),
            "wikipediaUrl": (cache.get(fig["slug"]) or {}).get("wikipedia_url"),
        })
    return out

def person_summary(p):
    return {
        "id": p["id"],
        "personCode": p["personCode"],
        "fullName": p["fullName"],
        "nickname": p.get("nickname"),
        "romanizedName": p["romanizedName"],
        "mediaPortrait": p["mediaPortrait"],
    }

def category_summary(c):
    return {"id": c["id"], "categoryCode": c["categoryCode"], "name": c["name"]}

def gen_projects(persons, categories, n=N):
    out = []
    used_codes = set()
    for i in range(n):
        # 10% untitled (no person); 90% linked to a person
        link_person = random.random() > 0.10
        person = pick(persons) if link_person else None
        cats = random.sample(categories, k=random.randint(1, 3))

        if person:
            base = slugify(person["personCode"])
            name = f"کۆکراوەی {person['fullName']} — {cats[0]['name']}"
        else:
            base = "UNTITLED"
            name = f"کۆکراوەی بێ ناونیشان — {cats[0]['name']}"

        # ensure unique projectCode
        seq = 1
        code = f"{base}_PROJ_{seq:06d}"
        while code in used_codes:
            seq += 1
            code = f"{base}_PROJ_{seq:06d}"
        used_codes.add(code)

        created = random_instant(2020, 2025)
        updated = created + timedelta(days=random.randint(0, 400))

        descr = (
            f"کۆکراوەیەکی هەڵبژێردراوی بەرهەمەکانی {cats[0]['name']}"
            + (f"، پەیوەست بە {person['fullName']}" if person else "")
            + f"، لە ماوەی ساڵانی {created.year}–{updated.year}دا کۆکراوەتەوە. "
            "بەرهەمەکان لە ئەرشیفە فیزیکییەکانی "
            f"{pick(CITIES)} دیجیتاڵ کراون و لەلایەن هاوبەشە کۆمەڵگاییەکانەوە "
            "بەخشراون."
        )

        tags = sample(
            ["دیجیتاڵکراو", "ئەرشیف", "تۆمارکردنی بواری", "ستۆدیۆ",
             "میژووی زارەکی", "سەرچاوەی سەرەکی", "نایاب", "نەوروز",
             cats[0]["name"], pick(KURDISH_DIALECTS)],
            2, 6,
        )
        keywords = sample(
            ["میراتی کوردی", "دەستنووس", "نەریتی زارەکی", "فۆلکلۆر",
             "کۆچبەری", "سەدەی بیستەم", "سەردەمی نوێ", "ئاینی",
             "بێبڕیار", "زمانی کەمێنە", cats[0]["name"]],
            2, 6,
        )

        out.append({
            "id": i + 1,
            "projectCode": code,
            "projectName": name,
            "description": descr,
            "tags": tags,
            "keywords": keywords,
            "person": person_summary(person) if person else None,
            "categories": [category_summary(c) for c in cats],
            "mediaCounts": {
                "audios": random.randint(0, 25),
                "videos": random.randint(0, 12),
                "texts": random.randint(0, 18),
                "images": random.randint(0, 40),
                "total": 0,
            },
            "createdAt": iso(created),
            "updatedAt": iso(updated),
        })
        out[-1]["mediaCounts"]["total"] = sum(
            v for k, v in out[-1]["mediaCounts"].items() if k != "total"
        )
    return out

def base_media_titles():
    en = pick([
        "گۆرانییەکانی هەورامان", "مەقاماتی سلێمانی", "نامەکانی هەڵەبجە",
        "دەنگەکانی موکریان", "ئەکوەکانی نەوروز", "دیوانی گۆران",
        "چیرۆکەکان لە جزیر", "ئاوازی تەنبوور", "دەنگبێژەکانی بۆتان",
        "بەخەواندنی بەهدینان", "ستری زەماوەندی گەرمیان",
        "خوتبە لە برادۆست", "تێبینی بواری هەولێر", "لاپەڕەکانی دەستنووس",
        "چاوپێکەوتن لەگەڵ پیران", "ئاگری نەوروز", "ژەنینی دەف",
        "بازاڕی کۆنی سلێمانی", "بیرەوەرییەکانی هەڵەبجە", "لێکۆڵینەوەی شێوەزار",
    ])
    alt = pick([
        "تۆمارکراوی ئەرشیفی", "نوسخەی کارکردن", "چاپی نوێبکراوەوە",
        "تۆماری بواری", "ماستەری ستۆدیۆ", "بەخشینی کۆمەڵگە",
    ])
    return en, alt

PROJECT_TITLE_PREFIX = [
    "AUD", "VID", "TXT", "IMG",
]

def media_code(project_code, kind_short, index):
    """e.g. SEWA_PROJ_000001_AUD_RAW_V1_000003."""
    return f"{project_code}_{kind_short}_RAW_V1_{index:06d}"

def rights_block():
    return {
        "copyright": pick([
            "© ئەرشیفی KHI ٢٠٢٤", "© خێزانی بەخشەر",
            "© میراتی هونەرمەند", "© دامەزراوەی کلتووری گشتی",
            "© متمانەی میراتی کۆمەڵگە",
        ]),
        "rightOwner": pick([
            "پلاتفۆڕمی ئەرشیفی KHI", "خێزانی بەخشەر",
            "میراتی هونەرمەند", "دامەزراوەی تۆمارکردنی ڕەسەن",
            "هاوبەشی کۆمەڵگە",
        ]),
        "dateCopyrighted": iso(random_instant(1990, 2025)),
        "licenseType": pick(LICENSE_TYPES),
        "availability": pick(AVAILABILITY),
        "owner": pick(["ئەرشیفی KHI", "بەخشینی خێزان", "هاوبەشی دامەزراوەیی"]),
        "publisher": pick(["ئەرشیفی KHI", "چاپی تایبەت", "دامەزراوەی کلتووری",
                           "هاوبەشی کۆمەڵگە", "چاپخانەی زانکۆ"]),
    }

def gen_audios(projects, n=N):
    out = []
    used_codes = set()
    for i in range(n):
        proj = pick(projects)
        seq = 1
        code = media_code(proj["projectCode"], "AUD", seq)
        while code in used_codes:
            seq += 1
            code = media_code(proj["projectCode"], "AUD", seq)
        used_codes.add(code)

        en, alt = base_media_titles()
        rom = romanize(en)
        ck = make_central_kurdish_title(en)

        d_created = random_instant(1960, 2024)
        d_pub = d_created + timedelta(days=random.randint(0, 365 * 3))
        d_mod = d_pub + timedelta(days=random.randint(0, 600))

        contributors = sample(
            [f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}" for _ in range(8)],
            1, 4,
        )

        item = {
            "id": i + 1,
            "audioCode": code,
            "projectCode": proj["projectCode"],
            "projectName": proj["projectName"],
            "personMediaPortrait": proj["person"]["mediaPortrait"] if proj.get("person") else None,
            "person": proj.get("person"),
            "categories": proj["categories"],
            "originTitle": en,
            "alterTitle": alt,
            "centralKurdishTitle": ck,
            "romanizedTitle": rom,
            "form": pick(["دەنگی", "ئامێریی", "دەنگی+ئامێریی", "بێ ئامێر"]),
            "typeOfBasta": pick(BASTAS),
            "typeOfMaqam": pick(MAQAMS),
            "genre": sample(GENRES_MUSIC, 1, 3),
            "abstractText": (
                f"تۆمارکراوی «{en}» کە بە شێوەزاری {pick(KURDISH_DIALECTS)} نواندووە، "
                f"لە ساڵی {d_created.year} لە "
                f"{pick(['کۆبوونەوەی کۆمەڵگە', 'زەماوەند', 'ستۆدیۆی ڕادیۆی شارۆچکە', 'ماڵی کەسی', 'ڕێوڕەسمی ئاینی'])}"
                f" دا لە {pick(CITIES)} گیراوەتە بەر دەنگ."
            ),
            "description": (
                "وەرگرتنی WAVی بێزیان لە کاسێت/ڕیل بۆ ڕیلی ڕەسەن. "
                "میتاداتاکان لە یاداشتی چاوپێکەوتنەکانەوە کۆکراونەتەوە؛ "
                "دەقەکان بەشێکیان نووسراونەتەوە."
            ),
            "speaker": f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}",
            "producer": maybe(0.6, f"{pick(FIRST_NAMES_M)} {pick(SURNAMES)}"),
            "composer": maybe(0.5, f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}"),
            "poet": maybe(0.5, f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}"),
            "contributors": contributors,
            "language": pick(LANGUAGES),
            "dialect": pick(KURDISH_DIALECTS),
            "typeOfComposition": pick(["نەریتی", "دانراو", "ئاکتیڤ", "وەرگرتراو"]),
            "typeOfPerformance": pick(["تاکە کەس", "دووانە", "گرووپ", "کۆڕەسپانە"]),
            "lyrics": maybe(0.7, "دەقەکان بە نووسینی ڕەسەنیدا پارێزراون؛ وەرگێڕانی بەشێکی بەر دەستەنییە."),
            "recordingVenue": pick(["تۆماری بواری", "ستۆدیۆی ڕادیۆی سلێمانی", "ماڵی کەسی",
                                    "حەوشەی مزگەوت", "تەکیە", "ناوەندی کلتووری", "کۆبوونەوەی کراوە"]),
            "city": pick(CITIES),
            "region": pick(REGIONS),
            "audience": pick(AUDIENCES),
            "tags": sample(["چاککراوەتەوە", "لە کاسێتەوە", "لە ڕیلەوە", "بواری", "ستۆدیۆ",
                            "بەخشینی کۆمەڵگە", "نایاب", "کوالیتی بەرز"], 1, 4),
            "keywords": sample(["فۆلکلۆر", "نەریتی", "مۆسیقای کوردی", "نەریتی زارەکی",
                                "دەنگی", "ئامێریی", "دەنگبێژ"], 2, 5),
            "dateCreated": iso(d_created),
            "datePublished": iso(d_pub),
            "dateModified": iso(d_mod),
            "audioFileUrl": f"{S3_BASE}/audios/{code}.wav",
        }
        item.update(rights_block())
        out.append(item)
    return out

def gen_videos(projects, n=N):
    out = []
    used_codes = set()
    for i in range(n):
        proj = pick(projects)
        seq = 1
        code = media_code(proj["projectCode"], "VID", seq)
        while code in used_codes:
            seq += 1
            code = media_code(proj["projectCode"], "VID", seq)
        used_codes.add(code)

        en, alt = base_media_titles()
        rom = romanize(en)
        ck = make_central_kurdish_title(en)

        d_created = random_instant(1965, 2024)
        d_mod = d_created + timedelta(days=random.randint(0, 600))
        d_pub = d_created + timedelta(days=random.randint(0, 365 * 3))

        item = {
            "id": i + 1,
            "videoCode": code,
            "projectCode": proj["projectCode"],
            "projectName": proj["projectName"],
            "personMediaPortrait": proj["person"]["mediaPortrait"] if proj.get("person") else None,
            "person": proj.get("person"),
            "categories": proj["categories"],
            "originalTitle": en,
            "alternativeTitle": alt,
            "titleInCentralKurdish": ck,
            "romanizedTitle": rom,
            "subject": sample(["سەمای فۆلکلۆری", "ڕێوڕەسم", "چاوپێکەوتن", "ئاهەنگ",
                                "نواندنی مۆسیقی", "میژووی زارەکی", "نەوروز", "زەماوەند",
                                "ڕەفتاری ئاینی"], 1, 3),
            "genre": sample(GENRES_VIDEO, 1, 3),
            "event": maybe(0.6, pick(["ئاهەنگی نەوروز", "زەماوەندی ناوخۆیی", "خوێندنی مەولوود",
                                       "کۆنفرانسی ئەدەبی کوردی", "سەمای تەکیە",
                                       "ساڵیادی هەڵەبجە"])),
            "location": pick(CITIES),
            "description": (
                f"فووتاژی دۆکیومێنتاری لە ساڵی {d_created.year} "
                f"کە {pick(['ڕێوڕەسمی ئاینی', 'ئاهەنگی کلتووری', 'کۆبوونەوەی کۆمەڵگە', 'چاوپێکەوتنی تایبەتی'])} "
                f"لە {pick(CITIES)} پیشان دەدات. "
                f"بە {pick(['SD', 'HD', '4K'])} تۆمار کراوە و لە "
                f"{pick(['VHS', 'Betacam', 'DV', 'miniDV'])}ەوە چاککراوەتەوە."
            ),
            "personShownInVideo": maybe(0.7, f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}"),
            "colorOfVideo": sample(["ڕەنگاوڕەنگ", "ڕەش و سپی", "سێپیا", "ڕەنگکراوەتەوە"], 1, 2),
            "language": pick(LANGUAGES),
            "dialect": pick(KURDISH_DIALECTS),
            "subtitle": maybe(0.5, pick(["ژێرنووسی ئینگلیزی", "ژێرنووسی عەرەبی", "سۆرانی چەسپێنراو", "هیچ"])),
            "creatorArtistDirector": f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}",
            "producer": maybe(0.6, f"{pick(FIRST_NAMES_M)} {pick(SURNAMES)}"),
            "contributor": maybe(0.5, f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}"),
            "audience": pick(AUDIENCES),
            "tags": sample(["چاککراوەتەوە", "لە تەیپەوە", "تۆماری بواری", "ستۆدیۆ",
                             "بەخشینی کۆمەڵگە", "نایاب", "کوالیتی بەرز", "تێبینیکراو"], 1, 4),
            "keywords": sample(["دۆکیومێنتاری", "ئاهەنگ", "مۆسیقا", "میراتی کوردی",
                                 "ئەتنۆگرافی", "میژووی زارەکی"], 2, 5),
            "whereThisVideoUsed": sample(["پیشانگای ٢٠٢٢", "پۆڕتاڵی ئۆنڵاین", "بەشی فێرکاری",
                                            "نمایشی کۆنفرانس", "پەخشی گشتی"], 0, 3),
            "duration": f"{random.randint(0, 1)}:{random.randint(10, 59):02d}:{random.randint(0, 59):02d}",
            "dateCreated": iso(d_created),
            "dateModified": iso(d_mod),
            "datePublished": iso(d_pub),
            "videoFileUrl": f"{S3_BASE}/videos/{code}.mp4",
        }
        item.update(rights_block())
        item["usageRights"] = pick(["تەنیا بۆ فێرکاری", "بەکارهێنانی گشتی ڕێگەپێدراو",
                                      "مۆڵەت پێویستە", "تەنیا بۆ نیشاندان — بێ وەرگێڕان"])
        out.append(item)
    return out

def gen_texts(projects, n=N):
    out = []
    used_codes = set()
    for i in range(n):
        proj = pick(projects)
        seq = 1
        code = media_code(proj["projectCode"], "TXT", seq)
        while code in used_codes:
            seq += 1
            code = media_code(proj["projectCode"], "TXT", seq)
        used_codes.add(code)

        en, alt = base_media_titles()
        rom = romanize(en)
        ck = make_central_kurdish_title(en)

        d_created = random_instant(1880, 2024)
        d_pub = d_created + timedelta(days=random.randint(0, 365 * 5))
        d_mod = d_pub + timedelta(days=random.randint(0, 600))
        d_print = d_pub - timedelta(days=random.randint(0, 365))

        item = {
            "id": i + 1,
            "textCode": code,
            "projectCode": proj["projectCode"],
            "projectName": proj["projectName"],
            "personMediaPortrait": proj["person"]["mediaPortrait"] if proj.get("person") else None,
            "person": proj.get("person"),
            "categories": proj["categories"],
            "originalTitle": en,
            "alternativeTitle": alt,
            "titleInCentralKurdish": ck,
            "romanizedTitle": rom,
            "subject": sample(["ئاین", "میژوو", "شیعر", "زمانەوانی", "ئەتنۆگرافی",
                                "یاسا", "ژیاننامە", "فۆلکلۆر", "نۆتاسیۆنی مۆسیقا"], 1, 3),
            "genre": sample(GENRES_TEXT, 1, 3),
            "documentType": pick(DOCUMENT_TYPES),
            "description": (
                f"{pick(['سکانکراو', 'پرۆسەی OCR کراو', 'وەرگرتراو', 'وێنەگیراو'])}ی "
                f"{pick(['دەستنووسێک', 'کتێبێکی چاپکراو', 'پەرتووکچەیەک', 'گۆڤارێک'])} "
                f"لە ساڵی {d_created.year}، {random.randint(20, 480)} لاپەڕە، "
                f"بە نووسینی {pick(SCRIPTS)}. سەرچاوەکەی بۆ {pick(CITIES)} دەگەڕێتەوە."
            ),
            "script": pick(SCRIPTS),
            "transcription": maybe(0.6, "وەرگرتنی بەشێکی بەردەستە؛ چاپی دیجیتاڵی تەواو لە ئامادەکاریدایە."),
            "isbn": maybe(0.3, f"978-{random.randint(0, 9)}-{random.randint(10000, 99999)}-{random.randint(100, 999)}-{random.randint(0, 9)}"),
            "edition": pick(["یەکەم", "دووەم", "سێیەم", "چاپی نوێبکراوەوە", "چاپی ڕەخنەیی", "وێنەکپی"]),
            "volume": maybe(0.4, f"بەرگی {random.randint(1, 5)}"),
            "series": maybe(0.3, "زنجیرەی میراتی KHI"),
            "language": pick(LANGUAGES),
            "dialect": pick(KURDISH_DIALECTS),
            "author": f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}",
            "contributors": f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}؛ {pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}",
            "printingHouse": pick(["چاپخانەی سلێمانی", "چاپخانەی هەولێر",
                                     "چاپخانەی کلتووری مەهاباد", "چاپخانەی ئامەد",
                                     "چاپخانەی سنە", "چاپخانەی بێروت"]),
            "audience": pick(AUDIENCES),
            "tags": sample(["دیجیتاڵکراو", "سکانکراو", "OCR کراو", "وەرگرتراو",
                             "تێبینیکراو", "نایاب", "چاپی یەکەم", "وێنەکپی"], 1, 4),
            "keywords": sample(["ئەدەبی کوردی", "دەستنووس", "دەقی ئاینی",
                                 "سەرچاوەی زمانەوانی", "میژوو"], 2, 5),
            "pageCount": random.randint(8, 720),
            "dateCreated": iso(d_created),
            "printDate": iso(d_print),
            "dateModified": iso(d_mod),
            "datePublished": iso(d_pub),
            "textFileUrl": f"{S3_BASE}/texts/{code}.pdf",
        }
        item.update(rights_block())
        item["usageRights"] = pick(["تەنیا بۆ توێژینەوە", "گەیشتنی گشتی", "تەنیا بۆ نیشاندان",
                                      "سنووردار؛ مۆڵەت پێویستە"])
        out.append(item)
    return out

def gen_images(projects, n=N):
    out = []
    used_codes = set()
    for i in range(n):
        proj = pick(projects)
        seq = 1
        code = media_code(proj["projectCode"], "IMG", seq)
        while code in used_codes:
            seq += 1
            code = media_code(proj["projectCode"], "IMG", seq)
        used_codes.add(code)

        en, alt = base_media_titles()
        rom = romanize(en)
        ck = make_central_kurdish_title(en)

        d_created = random_instant(1900, 2024)
        d_mod = d_created + timedelta(days=random.randint(0, 600))
        d_pub = d_created + timedelta(days=random.randint(0, 365 * 5))

        item = {
            "id": i + 1,
            "imageCode": code,
            "projectCode": proj["projectCode"],
            "projectName": proj["projectName"],
            "personMediaPortrait": proj["person"]["mediaPortrait"] if proj.get("person") else None,
            "person": proj.get("person"),
            "categories": proj["categories"],
            "originalTitle": en,
            "alternativeTitle": alt,
            "titleInCentralKurdish": ck,
            "romanizedTitle": rom,
            "subject": sample(["پۆرترێ", "دیمەنی سروشت", "ئەندازیاری بینا", "ڕێوڕەسم",
                                "ئامێر", "جل و بەرگ", "لاپەڕەی دەستنووس", "ئاهەنگ"], 1, 3),
            "form": pick(["وێنە", "پلێتی شووشە", "نێگەتیڤ", "سلایس", "دیجیتاڵی ڕەسەن", "پۆستکارت"]),
            "genre": sample(GENRES_IMAGE, 1, 3),
            "event": maybe(0.5, pick(["نەوروز", "زەماوەند", "مەولوود", "ناشتن", "ئاهەنگ",
                                       "کۆنفرانس", "ڕێوڕەسمی ئاینی"])),
            "location": pick(CITIES),
            "description": (
                f"وێنەیەکی {pick(['ڕەش و سپی', 'سێپیا', 'ڕەنگاوڕەنگ'])} لە ساڵی {d_created.year}، "
                f"کە {pick(['پۆرترێی پیرێک', 'دیمەنی زەماوەند', 'بینایەکی گەلێی', 'ئامێرێک لە نزیکەوە', 'لاپەڕەیەکی دەستنووس', 'ئاگری نەوروز'])} نیشان دەدات. "
                f"بە {pick(['600 dpi', '1200 dpi', '2400 dpi'])} لە ڕەسەنی "
                f"{pick(['پلێتی شووشە', 'چاپی سیلڤەری ژیلاتین', 'سلایسی ٣٥mm', 'پۆستکارت'])}دا سکان کراوە."
            ),
            "personShownInImage": maybe(0.7, f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}"),
            "colorOfImage": sample(["ڕەنگاوڕەنگ", "ڕەش و سپی", "سێپیا", "بە دەست ڕەنگکراو"], 1, 2),
            "manufacturer": maybe(0.6, pick(["Kodak", "Leica", "Zeiss Ikon", "Canon", "Nikon", "Sony"])),
            "model": maybe(0.5, pick(["Retina IIa", "Leica M3", "Contax II", "Nikkormat",
                                       "EOS 5D", "A7R IV", "Brownie No. 2"])),
            "lens": maybe(0.4, pick(["50mm f/2", "35mm f/1.4", "85mm f/1.8", "28mm f/2.8",
                                      "24-70mm f/2.8 زووم"])),
            "creatorArtistPhotographer": f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}",
            "contributor": maybe(0.5, f"{pick(FIRST_NAMES_M + FIRST_NAMES_F)} {pick(SURNAMES)}"),
            "audience": pick(AUDIENCES),
            "photostory": maybe(0.3, "بەشێک لە کۆمەڵە وێنەیەک کە ژیانی ڕۆژانە لە سەرتاسەری ناوچەکەدا لەو سەردەمەدا بەڵگەنامە دەکات."),
            "tags": sample(["سکانکراو", "چاککراوەتەوە", "لە نێگەتیڤەوە", "لە پلێتی شووشەوە",
                             "وردبینی بەرز", "تێبینیکراو", "نایاب", "ڕەنگ چاککراو"], 1, 4),
            "keywords": sample(["وێنە", "میراتی کوردی", "ئەتنۆگرافی",
                                 "دۆکیومێنتاری", "ستۆدیۆ", "بواری"], 2, 5),
            "whereThisImageUsed": sample(["پیشانگای ٢٠٢٢", "پۆڕتاڵی ئۆنڵاین", "وێنە بۆ کتێب",
                                            "بەشی فێرکاری", "پەخشی گشتی"], 0, 3),
            "dateCreated": iso(d_created),
            "dateModified": iso(d_mod),
            "datePublished": iso(d_pub),
            "imageFileUrl": photo_url(code, 1200, 800),
        }
        item.update(rights_block())
        item["usageRights"] = pick(["گەیشتنی گشتی", "تەنیا بۆ توێژینەوە",
                                      "بەکارهێنانی فێرکاری", "تەنیا بۆ نیشاندان — بێ وەرگێڕان"])
        out.append(item)
    return out

def write_json(filename, data):
    path = OUT_DIR / filename
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    return path, len(data)

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    categories = gen_categories(N)
    persons = gen_persons(N)
    projects = gen_projects(persons, categories, N)
    audios = gen_audios(projects, N)
    videos = gen_videos(projects, N)
    texts = gen_texts(projects, N)
    images = gen_images(projects, N)

    written = []
    for name, data in [
        ("categories.json", categories),
        ("persons.json", persons),
        ("projects.json", projects),
        ("audios.json", audios),
        ("videos.json", videos),
        ("texts.json", texts),
        ("images.json", images),
    ]:
        path, count = write_json(name, data)
        written.append((name, count, path.stat().st_size))

    width = max(len(n) for n, _, _ in written)
    print(f"{'file':<{width}}  records   bytes")
    print(f"{'-' * width}  -------   --------")
    total_bytes = 0
    for name, count, size in written:
        total_bytes += size
        print(f"{name:<{width}}  {count:>7}   {size:>8,}")
    print(f"{'TOTAL':<{width}}  {sum(c for _, c, _ in written):>7}   {total_bytes:>8,}")

if __name__ == "__main__":
    main()
