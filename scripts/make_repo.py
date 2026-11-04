"""
Builds the extension repository folder (the content of the `repo` branch):

  repo/
    index.min.json   <- link to paste in Tachimanga / Mihon
    index.json
    repo.json        <- repo metadata + signing key fingerprint (required by Mihon)
    apk/tachiyomi-all.hitomi-vX.Y.Z.apk
    icon/eu.kanade.tachiyomi.extension.all.hitomi.png

Usage: python scripts/make_repo.py [output_dir]
Env:   KEY_STORE_FILE, KEY_STORE_PASSWORD, KEY_ALIAS (same defaults as hitomi/build.gradle.kts)
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "hitomi"
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "repo"


def gradle_value(name: str) -> str:
    text = (MODULE / "build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(rf'val {name} = "?([^"\n]+)"?', text)
    if not match:
        raise SystemExit(f"{name} not found in build.gradle.kts")
    return match.group(1).strip()


def source_id(name: str, lang: str, version_id: int = 1) -> int:
    key = f"{name.lower()}/{lang}/{version_id}"
    digest = hashlib.md5(key.encode()).digest()
    value = 0
    for i in range(8):
        value |= digest[i] << (8 * (7 - i))
    return value & 0x7FFFFFFFFFFFFFFF


def signing_fingerprint() -> str:
    keystore = os.environ.get("KEY_STORE_FILE") or str(ROOT / "signingkey.jks")
    cmd = [
        "keytool", "-exportcert",
        "-keystore", keystore,
        "-alias", os.environ.get("KEY_ALIAS", "hitomi"),
        "-storepass", os.environ.get("KEY_STORE_PASSWORD", "hitomi-ext"),
    ]
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        cmd[0] = str(Path(java_home) / "bin" / "keytool")
    der = subprocess.run(cmd, check=True, capture_output=True).stdout
    return hashlib.sha256(der).hexdigest()


def main() -> None:
    ext_name = gradle_value("extName")
    pkg = gradle_value("pkgName")
    code = int(gradle_value("extVersionCode"))
    version = f"{gradle_value('libVersion')}.{code}"
    apk_name = f"tachiyomi-all.hitomi-v{version}.apk"

    apk = MODULE / "build" / "outputs" / "apk" / "release" / f"tachiyomi-all.hitomi-v{version}-release.apk"
    if not apk.exists():
        raise SystemExit(f"APK not found: {apk} (run ./gradlew assembleRelease first)")

    factory = (MODULE / "src/main/kotlin/eu/kanade/tachiyomi/extension/all/hitomi/HitomiFactory.kt").read_text(encoding="utf-8")

    def langs_of(list_name: str) -> list[str]:
        block = re.search(rf"val {list_name} = listOf\((.*?)\n\s*\)", factory, re.S)
        if not block:
            raise SystemExit(f"{list_name} not found in HitomiFactory.kt")
        return re.findall(r'"([A-Za-z-]+)" to "[a-z]*"', block.group(1))

    # (source name, base url, languages) - must match HitomiFactory / the source classes
    source_sets = [
        ("Hitomi", "https://hitomi.la", langs_of("LANGUAGES")),
        ("nhentai.net", "https://nhentai.net", langs_of("NHENTAI_LANGUAGES")),
        ("Schale Network (Anchira)", "https://niyaniya.moe", ["all"]),
    ]
    sources = [
        {"name": name, "lang": lang, "id": str(source_id(name, lang)), "baseUrl": base_url}
        for name, base_url, langs in source_sets
        for lang in langs
    ]

    entry = {
        "name": f"Tachiyomi: {ext_name}",
        "pkg": pkg,
        "apk": apk_name,
        "lang": "all",
        "code": code,
        "version": version,
        "nsfw": 1,
        "sources": sources,
    }

    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "apk").mkdir(parents=True)
    (OUT / "icon").mkdir(parents=True)

    shutil.copy(apk, OUT / "apk" / apk_name)
    shutil.copy(MODULE / "src/main/res/mipmap-xxxhdpi/ic_launcher.png", OUT / "icon" / f"{pkg}.png")

    (OUT / "index.min.json").write_text(json.dumps([entry], ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    (OUT / "index.json").write_text(json.dumps([entry], ensure_ascii=False, indent=2), encoding="utf-8")

    repo_meta = {
        "meta": {
            "name": "Hitomi extension repo",
            "shortName": "Hitomi",
            "website": "https://hitomi.la",
            "signingKeyFingerprint": signing_fingerprint(),
        }
    }
    (OUT / "repo.json").write_text(json.dumps(repo_meta, indent=2), encoding="utf-8")
    (OUT / "README.md").write_text("Extension repository. Add `index.min.json` from this branch to your app.\n", encoding="utf-8")

    print(f"Repo written to {OUT}")
    print(json.dumps(entry, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
