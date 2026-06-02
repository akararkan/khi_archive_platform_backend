#!/usr/bin/env python3
"""Second probe — more Kurdish figures with alt spellings."""
import json
import time
import urllib.request
import urllib.parse
import urllib.error

CANDIDATES = [
    "Mahmud_Barzanji", "Abdurrahman_Sharafkandi", "Hemin_Mukriyani", "Hemin",
    "Nali", "Mahwi", "Yashar_Kemal", "Mehmed_Uzun", "Ahmed_Arif",
    "Bachtyar_Ali", "Mehrdad_Izady", "Refik_Saydam",
    "Said_Veroj", "Musa_Anter", "Mehmet_Uzun", "Yashar_Kaya",
    "Hejar", "Abdulrahman_Sharafkandi", "Hemin_Moukriani",
    "Sheikh_Said_(Kurdish_leader)", "Sheikh_Said_Piran",
    "Şexmûs_Hesen", "Mahmud_Yashar", "Salim_(Kurdish_poet)",
    "Mehrdad_Saleh", "Aziz_Mahmud_Esiri",
    "Shara_Bekes", "Kameran_Bedirxan", "Mîr_Celadet",
    "Hassan_Sheikholeslami", "Foad_Mostafa_Soltani", "Ehmedê_Xasî",
    "Abdullah_Çatlı", "Leyla_Zana", "Pawan_Durani",
    "Şêx_Seîd", "Mela_Cemîl",
    "Helbest", "Aziz_Mahmud_Hudayi", "Cemal_Nebez",
    "Ihsan_Nuri", "Simko_Shikak",
    "Mehrdad_Saleh", "Selim_Berekat",
    "Bachtyar_Ali", "Kurdish_poet", "Bashir_Mardochee",
    "Mela_Hesenê_Hêştî", "Mela_Hesen_Hişyar_Serdî",
    "Abdul_Razzaq_Bedirkhan", "Sureyya_Bedirkhan",
    "Salahaddin_Yağmurdereli", "Hesen_Hişyar", "Husein_Huzni_Mukriyani",
    "Hesen_Sirvan", "Nizamettin_Ariç", "Şakiro",
    "Hozan_Dilgesh", "Karapetê_Xaço", "Meryem_Xan",
    "Mihemed_Şêxo", "Hozan_Serhad", "Hozan_Bêkes",
    "Yıldız_Tilbe", "Aynur_Doğan", "Rojda", "Diyar_Dersim",
    "Ferhat_Tunç", "Sivan", "Sîpan_Xelat",
    "Ferzende_Beg", "Salahaddin_Demirtaş",
    "Selahattin_Demirtaş", "Ahmet_Türk", "Leyla_Zana",
    "Sabri_Eyyuboğlu", "Mehmet_Emin_Bozarslan",
    "Falakaddin_Kakai", "Falakeddin_Kakei",
]

def fetch(title, max_retries=3):
    url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(title, safe='')}"
    for attempt in range(max_retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "khi-seed-probe/1.0 (contact: archive@example.org)"})
            with urllib.request.urlopen(req, timeout=10) as r:
                data = json.loads(r.read().decode("utf-8"))
                if data.get("type") == "disambiguation":
                    return None
                img = (data.get("originalimage") or {}).get("source") or (data.get("thumbnail") or {}).get("source")
                return {
                    "title": data.get("title"),
                    "image": img,
                    "extract": data.get("extract", "")[:300],
                    "wikipedia_url": data.get("content_urls", {}).get("desktop", {}).get("page"),
                }
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code == 429:
                time.sleep(4 * (attempt + 1))
                continue
            return f"http {e.code}"
        except Exception as e:
            return f"err: {e}"
    return "rate-limited"

results = {}
for slug in CANDIDATES:
    time.sleep(0.6)
    r = fetch(slug)
    if r is None:
        status = "404"
    elif isinstance(r, str):
        status = r
    elif not r.get("image"):
        status = "no-image"
    else:
        status = "OK"
    title = r.get("title") if isinstance(r, dict) else ''
    print(f"  {status:<10} {slug:<35} {title}")
    results[slug] = r

with open("probe_results2.json", "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
