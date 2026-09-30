"""Download and unpack the external tools needed to build and test panamage-jxl.

Tools:
  * jextract 25 (Windows x64 early-access build from jdk.java.net)
  * libjxl 0.12.0 shared build for Windows x64 (GitHub release asset)
  * libjxl 0.12.0 shared libraries for Linux x86_64, taken from the Ubuntu
    20.04 packages of the GitHub release, plus the Brotli libraries they
    depend on (Ubuntu 20.04 archive)
  * a JDK 25 for Linux x86_64, to build and test in WSL

Every archive is verified against its published SHA-256 digest before it is
unpacked. Already unpacked tools are left untouched, so the script can be run
repeatedly.

Usage:
  python fetch_tools.py [--dest DIR]
"""

import argparse
import hashlib
import io
import json
import lzma
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

from tools_dir import DEFAULT_TOOLS_DIR, ENVIRONMENT_VARIABLE, tools_dir

JEXTRACT_URL = (
    "https://download.java.net/java/early_access/jextract/25/2/"
    "openjdk-25-jextract+2-4_windows-x64_bin.tar.gz"
)
JEXTRACT_DIR = "jextract-25"

LIBJXL_VERSION = "0.12.0"
LIBJXL_ASSET = "jxl-x64-windows.zip"
LIBJXL_RELEASE_API = (
    f"https://api.github.com/repos/libjxl/libjxl/releases/tags/v{LIBJXL_VERSION}"
)
LIBJXL_DIR = f"libjxl-{LIBJXL_VERSION}"

# Linux x86_64: libjxl from the Ubuntu 20.04 packages (glibc 2.29 or newer).
LIBJXL_LINUX_ASSET = "jxl-debs-amd64-ubuntu-20.04.tar"
LIBJXL_LINUX_DIR = f"libjxl-{LIBJXL_VERSION}-linux-x86_64"
# Real file name in the package -> file name in the bundle (the SONAME).
LIBJXL_LINUX_LIBRARIES = {
    "libjxl.so.0.12.0": "libjxl.so.0.12",
    "libjxl_cms.so.0.12.0": "libjxl_cms.so.0.12",
    "libjxl_threads.so.0.12.0": "libjxl_threads.so.0.12",
}

BROTLI_DEB = "libbrotli1_1.0.7-6ubuntu0.1_amd64.deb"
BROTLI_DEB_URL = f"http://archive.ubuntu.com/ubuntu/pool/main/b/brotli/{BROTLI_DEB}"
BROTLI_INDEX_URL = "http://archive.ubuntu.com/ubuntu/dists/focal-updates/main/binary-amd64/Packages.xz"
BROTLI_LIBRARIES = {
    "libbrotlicommon.so.1.0.7": "libbrotlicommon.so.1",
    "libbrotlidec.so.1.0.7": "libbrotlidec.so.1",
    "libbrotlienc.so.1.0.7": "libbrotlienc.so.1",
}

LINUX_JDK_PACKAGE_API = "https://api.azul.com/metadata/v1/zulu/packages/f588d0ab-4c15-4b9f-bb65-63f29c3a28d0"
LINUX_JDK_DIR = "jdk-25-linux-x86_64"


def http_get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "panamage-jxl-fetch-tools"})
    with urllib.request.urlopen(request) as response:
        return response.read()


def download(url: str, target: Path) -> None:
    if target.exists():
        print(f"Using cached {target}")
        return
    print(f"Downloading {url}")
    request = urllib.request.Request(url, headers={"User-Agent": "panamage-jxl-fetch-tools"})
    partial = target.with_name(target.name + ".part")
    with urllib.request.urlopen(request) as response, open(partial, "wb") as out:
        shutil.copyfileobj(response, out)
    partial.replace(target)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path: Path, expected: str) -> None:
    actual = sha256_of(path)
    if actual.lower() != expected.lower():
        path.unlink()
        raise SystemExit(f"SHA-256 mismatch for {path.name}: expected {expected}, got {actual}")
    print(f"SHA-256 OK: {path.name}")


def extract_zip(archive: Path, target: Path) -> None:
    """Extract a zip file; fall back to 7-Zip for methods like Deflate64."""
    with zipfile.ZipFile(archive) as zf:
        supported = {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED,
                     zipfile.ZIP_BZIP2, zipfile.ZIP_LZMA}
        if all(info.compress_type in supported for info in zf.infolist()):
            zf.extractall(target)
            return
    seven_zip = shutil.which("7z")
    if seven_zip is None:
        raise SystemExit(f"{archive.name} uses a compression method (e.g. Deflate64) "
                         "that Python cannot extract; install 7-Zip and put 7z on the PATH")
    print(f"Extracting {archive.name} with {seven_zip}")
    subprocess.run([seven_zip, "x", "-y", f"-o{target}", str(archive)],
                   check=True, stdout=subprocess.DEVNULL)


def unpack_single_root(archive: Path, dest: Path, tar_filter: str = "data") -> None:
    """Unpack an archive into dest, stripping a single top-level directory if present."""
    with tempfile.TemporaryDirectory(dir=dest.parent) as tmp:
        tmp_path = Path(tmp)
        if archive.name.endswith(".zip"):
            extract_zip(archive, tmp_path)
        else:
            with tarfile.open(archive) as tf:
                tf.extractall(tmp_path, filter=tar_filter)
        entries = list(tmp_path.iterdir())
        root = entries[0] if len(entries) == 1 and entries[0].is_dir() else tmp_path
        shutil.copytree(root, dest)


def fetch_jextract(dest_root: Path, downloads: Path) -> None:
    dest = dest_root / JEXTRACT_DIR
    if dest.exists():
        print(f"{dest} already exists, skipping jextract")
        return
    archive = downloads / JEXTRACT_URL.rsplit("/", 1)[1]
    download(JEXTRACT_URL, archive)
    expected = http_get(JEXTRACT_URL + ".sha256").decode("ascii").split()[0]
    verify(archive, expected)
    unpack_single_root(archive, dest)
    print(f"jextract unpacked to {dest}")


def fetch_libjxl(dest_root: Path, downloads: Path) -> None:
    dest = dest_root / LIBJXL_DIR
    if dest.exists():
        print(f"{dest} already exists, skipping libjxl")
        return
    release = json.loads(http_get(LIBJXL_RELEASE_API))
    asset = next((a for a in release["assets"] if a["name"] == LIBJXL_ASSET), None)
    if asset is None:
        raise SystemExit(f"Release asset {LIBJXL_ASSET} not found")
    archive = downloads / LIBJXL_ASSET
    download(asset["browser_download_url"], archive)
    digest = asset.get("digest") or ""
    if digest.startswith("sha256:"):
        verify(archive, digest.removeprefix("sha256:"))
    else:
        print(f"No published digest for {LIBJXL_ASSET}; SHA-256 is {sha256_of(archive)}")
    unpack_single_root(archive, dest)
    print(f"libjxl unpacked to {dest}")


def extract_deb(deb: bytes, wanted: set[str]) -> dict[str, bytes]:
    """Return the regular files with the given base names from a Debian package.

    A .deb file is an ar archive whose data.tar.* member holds the files.
    """
    if not deb.startswith(b"!<arch>\n"):
        raise SystemExit("Not a Debian package")
    found: dict[str, bytes] = {}
    pos = 8
    while pos + 60 <= len(deb):
        name = deb[pos:pos + 16].decode("ascii").strip().rstrip("/")
        size = int(deb[pos + 48:pos + 58].decode("ascii").strip())
        body = deb[pos + 60:pos + 60 + size]
        if name.startswith("data.tar"):
            with tarfile.open(fileobj=io.BytesIO(body)) as tf:
                for member in tf.getmembers():
                    base = member.name.rsplit("/", 1)[-1]
                    if member.isfile() and base in wanted:
                        found[base] = tf.extractfile(member).read()
        pos += 60 + size + (size & 1)
    missing = wanted - found.keys()
    if missing:
        raise SystemExit(f"Missing in package: {sorted(missing)}")
    return found


def sha256_from_ubuntu_index(index_url: str, filename: str) -> str:
    """Look up the SHA-256 of a package in an Ubuntu Packages.xz index."""
    index = lzma.decompress(http_get(index_url)).decode("utf-8")
    for stanza in index.split("\n\n"):
        fields = dict(line.split(": ", 1) for line in stanza.splitlines() if ": " in line)
        if fields.get("Filename", "").endswith("/" + filename):
            return fields["SHA256"]
    raise SystemExit(f"{filename} not found in {index_url}")


def fetch_libjxl_linux(dest_root: Path, downloads: Path) -> None:
    dest = dest_root / LIBJXL_LINUX_DIR
    if dest.exists():
        print(f"{dest} already exists, skipping libjxl for Linux")
        return
    release = json.loads(http_get(LIBJXL_RELEASE_API))
    asset = next((a for a in release["assets"] if a["name"] == LIBJXL_LINUX_ASSET), None)
    if asset is None:
        raise SystemExit(f"Release asset {LIBJXL_LINUX_ASSET} not found")
    archive = downloads / LIBJXL_LINUX_ASSET
    download(asset["browser_download_url"], archive)
    verify(archive, asset["digest"].removeprefix("sha256:"))
    with tarfile.open(archive) as tf:
        member = next(m for m in tf.getmembers() if m.name.startswith("libjxl_") and m.name.endswith("_amd64.deb"))
        libjxl_deb = tf.extractfile(member).read()

    brotli_deb = downloads / BROTLI_DEB
    download(BROTLI_DEB_URL, brotli_deb)
    verify(brotli_deb, sha256_from_ubuntu_index(BROTLI_INDEX_URL, BROTLI_DEB))

    libjxl_files = extract_deb(libjxl_deb, set(LIBJXL_LINUX_LIBRARIES) | {"copyright"})
    brotli_files = extract_deb(brotli_deb.read_bytes(), set(BROTLI_LIBRARIES) | {"copyright"})
    with tempfile.TemporaryDirectory(dir=dest_root) as tmp:
        staging = Path(tmp) / LIBJXL_LINUX_DIR
        (staging / "lib").mkdir(parents=True)
        (staging / "licenses").mkdir()
        for files, names in ((libjxl_files, LIBJXL_LINUX_LIBRARIES), (brotli_files, BROTLI_LIBRARIES)):
            for real_name, soname in names.items():
                (staging / "lib" / soname).write_bytes(files[real_name])
        (staging / "licenses" / "libjxl.copyright").write_bytes(libjxl_files["copyright"])
        (staging / "licenses" / "brotli.copyright").write_bytes(brotli_files["copyright"])
        staging.rename(dest)
    print(f"libjxl for Linux unpacked to {dest}")


def fetch_linux_jdk(dest_root: Path, downloads: Path) -> None:
    dest = dest_root / LINUX_JDK_DIR
    if dest.exists():
        print(f"{dest} already exists, skipping the Linux JDK")
        return
    package = json.loads(http_get(LINUX_JDK_PACKAGE_API))
    archive = downloads / package["name"]
    download(package["download_url"], archive)
    verify(archive, package["sha256_hash"])
    # The "tar" filter keeps symbolic links and Unix permissions of the JDK.
    unpack_single_root(archive, dest, tar_filter="tar")
    print(f"Linux JDK unpacked to {dest}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dest", type=Path, default=None,
                        help=f"target directory (default: ${ENVIRONMENT_VARIABLE} or {DEFAULT_TOOLS_DIR})")
    args = parser.parse_args()
    dest_root = tools_dir(args.dest)
    downloads = dest_root / "_downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    fetch_jextract(dest_root, downloads)
    fetch_libjxl(dest_root, downloads)
    fetch_libjxl_linux(dest_root, downloads)
    fetch_linux_jdk(dest_root, downloads)
    return 0


if __name__ == "__main__":
    sys.exit(main())
