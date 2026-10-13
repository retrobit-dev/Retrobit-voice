#!/usr/bin/env python3
"""
Pemeriksa konsistensi proyek Retrobit Voice.

Sandbox pengembangan tidak punya Android SDK, jadi skrip ini dipakai untuk
menangkap kesalahan yang paling sering terjadi SEBELUM build di Android Studio:

  1. R.string / R.drawable / R.layout / R.id / R.xml yang tidak ada
  2. Kelas yang disebut di AndroidManifest.xml tetapi berkasnya tidak ada
  3. Referensi @string/@drawable/@xml/@color/@mipmap/@style di dalam XML
  4. Keseimbangan kurung kurawal & tanda kutip pada berkas Kotlin
  5. Kunci string yang ada di values/ tetapi belum diterjemahkan di values-en/
  6. Keputusan arsitektur yang mudah terulang tanpa sengaja: penanganan inset
     edge-to-edge (wajib sejak targetSdk 35) dan larangan QUERY_ALL_PACKAGES

Jalankan: python3 tools/verify_project.py
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java"
RES = ROOT / "app/src/main/res"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"

errors: list[str] = []
warnings: list[str] = []


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ---------- 1. Kumpulkan nama resource yang benar-benar ada ----------
def resource_names(kind_dir: str) -> set[str]:
    names: set[str] = set()
    for path in (RES / kind_dir).glob("*.xml"):
        names.add(path.stem)
    return names


drawables = resource_names("drawable")
layouts = resource_names("layout")
xmls = resource_names("xml")
mipmaps = resource_names("mipmap-anydpi-v26")

strings_id: dict[str, str] = {}
strings_en: dict[str, str] = {}
for path, target in ((RES / "values/strings.xml", strings_id), (RES / "values-en/strings.xml", strings_en)):
    if not path.exists():
        errors.append(f"resources: {path.relative_to(ROOT)} tidak ditemukan")
        continue
    for node in ET.parse(path).getroot().findall("string"):
        target[node.attrib["name"]] = node.text or ""

colors = set()
themes = set()
for values_file in (RES / "values").glob("*.xml"):
    root = ET.parse(values_file).getroot()
    colors |= {n.attrib["name"] for n in root.findall("color")}
    themes |= {n.attrib["name"] for n in root.findall("style")}

# id dari layout XML (android:id)
layout_ids: set[str] = set()
for path in (RES / "layout").glob("*.xml"):
    layout_ids |= set(re.findall(r'android:id="@\+?id/([A-Za-z0-9_]+)"', read(path)))

# ---------- 2. Periksa referensi di kode Kotlin ----------
kotlin_files = sorted(JAVA.rglob("*.kt"))
kotlin_sources = {p: read(p) for p in kotlin_files}
prefix_map = {
    "R.string.": strings_id,
    "R.drawable.": drawables,
    "R.layout.": layouts,
    "R.id.": layout_ids,
    "R.xml.": xmls,
    "R.mipmap.": mipmaps,
    "R.color.": colors,
    "R.style.": themes,
}

for path in kotlin_files:
    text = read(path)
    for prefix, known in prefix_map.items():
        for match in re.finditer(re.escape(prefix) + r"([A-Za-z0-9_]+)", text):
            name = match.group(1)
            if name not in known:
                errors.append(f"{path.relative_to(ROOT)}: {prefix}{name} tidak ditemukan")

# ---------- 3. Periksa keseimbangan kurung pada berkas Kotlin ----------
for path in kotlin_files:
    text = read(path)
    stripped = re.sub(r'"""[\s\S]*?"""', '""', text)          # buang raw string
    stripped = re.sub(r"'(\\.|[^'\\])'", "''", stripped)          # buang literal char ('"' dll)
    stripped = re.sub(r'"(\\.|[^"\\])*"', '""', stripped)      # buang string biasa
    stripped = re.sub(r"//[^\n]*", "", stripped)               # buang komentar baris
    stripped = re.sub(r"/\*[\s\S]*?\*/", "", stripped)         # buang komentar blok
    for open_ch, close_ch in (("{", "}"), ("(", ")"), ("[", "]")):
        if stripped.count(open_ch) != stripped.count(close_ch):
            errors.append(
                f"{path.relative_to(ROOT)}: jumlah '{open_ch}' ({stripped.count(open_ch)}) "
                f"!= '{close_ch}' ({stripped.count(close_ch)})",
            )

# ---------- 4. Periksa kelas yang disebut di manifest ----------
manifest_text = read(MANIFEST)
package = "dev.retrobit.voice"
for attr in ("android:name", "android:sessionService", "android:recognitionService"):
    for match in re.finditer(rf'{attr}="([^"]+)"', manifest_text):
        value = match.group(1)
        if attr != "android:name" and not value.startswith(package):
            continue
        if attr == "android:name" and not value.startswith(".") and not value.startswith(package):
            continue  # mis. androidx.core.content.FileProvider
        cls = value.lstrip(".")
        if value.startswith(package):
            cls = value[len(package) + 1 :]
        # Kelas boleh berada di berkas dengan nama berbeda (mis. beberapa kelas per berkas),
        # jadi kita cari deklarasinya di seluruh kode Kotlin.
        simple_name = cls.split(".")[-1]
        hit = any(
            re.search(rf"\b(class|interface|object|enum class)\s+{re.escape(simple_name)}\b", source)
            for source in kotlin_sources.values()
        )
        if not hit:
            errors.append(f"AndroidManifest.xml: kelas {value} tidak ditemukan di kode")

# ---------- 5. Periksa referensi @resource di semua XML ----------
xml_ref = re.compile(r"@(?P<kind>string|drawable|xml|color|mipmap|style|layout)/(?P<name>[A-Za-z0-9_.]+)")
known_by_kind = {
    "string": strings_id,
    "drawable": drawables,
    "xml": xmls,
    "color": colors,
    "mipmap": mipmaps,
    "style": themes,
    "layout": layouts,
}
for path in list(RES.rglob("*.xml")) + [MANIFEST]:
    text = read(path)
    for match in xml_ref.finditer(text):
        kind, name = match.group("kind"), match.group("name")
        if name.startswith("android:"):
            continue
        if name not in known_by_kind[kind]:
            errors.append(f"{path.relative_to(ROOT)}: @{kind}/{name} tidak ditemukan")

# ---------- 6. Cakupan terjemahan ----------
missing_en = sorted(set(strings_id) - set(strings_en))
if missing_en:
    warnings.append(f"values-en/ belum memuat {len(missing_en)} kunci: {', '.join(missing_en[:10])}")

# ---------- 7. Penjaga keputusan arsitektur ----------
# Kesalahan di bagian ini tidak menggagalkan kompilasi, jadi tanpa pemeriksaan
# seperti ini ia hanya terlihat di perangkat (atau saat review Play).
all_kotlin = "\n".join(kotlin_sources.values())
build_gradle = read(ROOT / "app/build.gradle.kts")
target_match = re.search(r"targetSdk\s*=\s*(\d+)", build_gradle)
target_sdk = int(target_match.group(1)) if target_match else None

# 7a. Android 15 memaksa tampilan edge-to-edge begitu targetSdk >= 35: jendela
# menggambar di belakang system bar, jadi inset harus ditangani sendiri.
# Lihat docs/ARCHITECTURE.md bagian "Edge-to-edge & inset".
if target_sdk is not None and target_sdk >= 35:
    if "enableEdgeToEdge()" not in all_kotlin:
        errors.append("targetSdk >= 35 tetapi enableEdgeToEdge() tidak dipanggil")
    if "windowInsetsPadding" not in all_kotlin and "safeDrawing" not in all_kotlin:
        errors.append(
            "targetSdk >= 35 tetapi tidak ada padding inset "
            "(windowInsetsPadding / WindowInsets.safeDrawing) — konten bisa tertutup "
            "navigasi bar atau keyboard",
        )

# 7b. Opt-out edge-to-edge sudah dimatikan di Android 16, jadi memakainya hanya
# memberi rasa aman yang salah.
for path in sorted(list(RES.rglob("*.xml")) + [MANIFEST] + list(kotlin_files)):
    if "windowOptOutEdgeToEdgeEnforcement" in read(path):
        errors.append(
            f"{path.relative_to(ROOT)}: memakai windowOptOutEdgeToEdgeEnforcement "
            "(sudah dimatikan di Android 16)",
        )

# 7c. QUERY_ALL_PACKAGES dibatasi kebijakan Play; blok <queries> yang terarah
# sudah cukup untuk mencari aplikasi peluncur.
if re.search(r"<uses-permission[^>]*android\.permission\.QUERY_ALL_PACKAGES", manifest_text):
    errors.append(
        "AndroidManifest.xml: QUERY_ALL_PACKAGES dideklarasikan lagi "
        "(dibatasi kebijakan Play, blok <queries> sudah cukup)",
    )

# ---------- Laporan ----------
print(f"Berkas Kotlin diperiksa : {len(kotlin_files)}")
print(f"String (id/en)          : {len(strings_id)}/{len(strings_en)}")
print(f"Drawable/layout/xml/id  : {len(drawables)}/{len(layouts)}/{len(xmls)}/{len(layout_ids)}")
print(f"targetSdk               : {target_sdk if target_sdk is not None else 'tidak ditemukan'}")
print()

for warning in warnings:
    print(f"PERINGATAN  {warning}")
for error in errors:
    print(f"KESALAHAN   {error}")

if errors:
    print(f"\n=> {len(errors)} kesalahan, {len(warnings)} peringatan")
    sys.exit(1)
print(f"\n=> Semua referensi konsisten ({len(warnings)} peringatan)")
