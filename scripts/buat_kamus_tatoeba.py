#!/usr/bin/env python3
"""
Membuat kamus kalimat Jepang -> Indonesia dari Tatoeba (tatoeba.org, lisensi CC BY 2.0 FR).
Dijalankan otomatis oleh GitHub Actions sebelum APK di-build.

Pemakaian:  python3 scripts/buat_kamus_tatoeba.py app/src/main/assets/tatoeba_ja_id.tsv

Hasil: satu pasangan per baris "jepang<TAB>indonesia". Kalau Tatoeba sedang tidak bisa diakses,
file kosong tetap dibuat dan build tetap jalan (aplikasi cukup memakai frasa manga + ML Kit).
"""
import bz2
import io
import os
import sys
import tarfile
import time
import urllib.request

BASE = "https://downloads.tatoeba.org/exports"
URL_JPN = f"{BASE}/per_language/jpn/jpn_sentences.tsv.bz2"
URL_IND = f"{BASE}/per_language/ind/ind_sentences.tsv.bz2"
URL_LINKS_PAIR = f"{BASE}/per_language/jpn/jpn-ind_links.tsv.bz2"
URL_LINKS_ALL = f"{BASE}/links.tar.bz2"

MAX_JP = 40    # balon manga pendek; kalimat panjang hampir tidak mungkin cocok persis
MAX_ID = 150


def log(msg):
    print(msg, flush=True)


def download(url, tries=3):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "manga-terjemah-build"})
            with urllib.request.urlopen(req, timeout=180) as r:
                data = r.read()
            log(f"  unduh {url}  ({len(data) / 1e6:.1f} MB)")
            return data
        except Exception as e:  # noqa: BLE001
            log(f"  gagal ({i + 1}/{tries}) {url}: {e}")
            time.sleep(5 * (i + 1))
    return None


def read_sentences(data):
    """id -> teks dari file *_sentences.tsv.bz2 (kolom: id, bahasa, teks)."""
    out = {}
    for line in bz2.decompress(data).decode("utf-8").splitlines():
        p = line.split("\t")
        if len(p) >= 3 and p[0].isdigit():
            out[int(p[0])] = p[2].strip()
    return out


def read_links_pair(data):
    pairs = []
    for line in bz2.decompress(data).decode("utf-8").splitlines():
        p = line.split("\t")
        if len(p) >= 2 and p[0].isdigit() and p[1].isdigit():
            pairs.append((int(p[0]), int(p[1])))
    return pairs


def read_links_all(data, jpn, ind):
    """links.tar.bz2 berisi links.csv (dipisah tab) untuk semua bahasa; saring jpn<->ind saja."""
    pairs = []
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:bz2") as tar:
        for m in tar.getmembers():
            if not m.isfile():
                continue
            f = tar.extractfile(m)
            for raw in io.TextIOWrapper(f, encoding="utf-8"):
                p = raw.rstrip("\n").split("\t")
                if len(p) < 2 or not p[0].isdigit() or not p[1].isdigit():
                    continue
                a, b = int(p[0]), int(p[1])
                if a in jpn and b in ind:
                    pairs.append((a, b))
    return pairs


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/tatoeba_ja_id.tsv"
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    header = "# Kalimat dari Tatoeba (https://tatoeba.org), lisensi CC BY 2.0 FR\n"

    log("Tatoeba: mengunduh kalimat Jepang & Indonesia…")
    jpn_raw, ind_raw = download(URL_JPN), download(URL_IND)
    if not jpn_raw or not ind_raw:
        log("PERINGATAN: Tatoeba tidak bisa diunduh, kamus Tatoeba dikosongkan.")
        open(out_path, "w", encoding="utf-8").write(header)
        return
    jpn, ind = read_sentences(jpn_raw), read_sentences(ind_raw)
    log(f"  {len(jpn)} kalimat Jepang, {len(ind)} kalimat Indonesia")

    links_raw = download(URL_LINKS_PAIR)
    pairs = read_links_pair(links_raw) if links_raw else []
    if not pairs:
        log("  file pasangan jpn-ind tidak ada, memakai links.tar.bz2 (besar)…")
        all_raw = download(URL_LINKS_ALL)
        pairs = read_links_all(all_raw, jpn, ind) if all_raw else []

    best = {}  # teks Jepang -> terjemahan (id Tatoeba terkecil menang, biasanya yang paling lama dicek)
    for a, b in sorted(pairs):
        jp, idn = jpn.get(a), ind.get(b)
        if not jp or not idn or len(jp) > MAX_JP or len(idn) > MAX_ID:
            continue
        if "\t" in jp or "\t" in idn:
            continue
        best.setdefault(jp, idn)

    with open(out_path, "w", encoding="utf-8") as f:
        f.write(header)
        for jp, idn in sorted(best.items(), key=lambda kv: len(kv[0])):
            f.write(f"{jp}\t{idn}\n")

    msg = f"Tatoeba: {len(pairs)} pasangan jpn-ind, {len(best)} masuk kamus (≤{MAX_JP} huruf) -> {out_path}"
    log(msg)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as s:
            s.write(f"### Kamus Tatoeba\n{msg}\n")


if __name__ == "__main__":
    main()
