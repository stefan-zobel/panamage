"""Checkout, build and packing of libjxl from source, shared by the scripts
that build the native libraries for platforms without libjxl binaries
(build_libjxl_macos.py, build_libjxl_linux_aarch64.py).

The pinned release is built with only the runtime libraries: libjxl,
libjxl_cms (with skcms) and libjxl_threads, plus the Brotli libraries they
need; Highway is linked statically.
"""

import hashlib
import io
import os
import shutil
import subprocess
import tarfile
import time
from pathlib import Path

LIBJXL_VERSION = "0.12.0"
LIBJXL_REPOSITORY = "https://github.com/libjxl/libjxl.git"
LIBJXL_TAG = f"v{LIBJXL_VERSION}"
LIBJXL_COMMIT = "a7a9c787341cf703dede03c2009fa460cae5e5df"
SUBMODULES = ["third_party/brotli", "third_party/highway", "third_party/skcms"]

# Options for every platform; the platform scripts add their own.
COMMON_CMAKE_OPTIONS = [
    "-DCMAKE_BUILD_TYPE=Release",
    "-DBUILD_SHARED_LIBS=ON",
    "-DBUILD_TESTING=OFF",
    "-DJPEGXL_ENABLE_TOOLS=OFF",
    "-DJPEGXL_ENABLE_JPEGLI=OFF",
    "-DJPEGXL_ENABLE_DOXYGEN=OFF",
    "-DJPEGXL_ENABLE_MANPAGES=OFF",
    "-DJPEGXL_ENABLE_BENCHMARK=OFF",
    "-DJPEGXL_ENABLE_EXAMPLES=OFF",
    "-DJPEGXL_ENABLE_JNI=OFF",
    "-DJPEGXL_ENABLE_SJPEG=OFF",
    "-DJPEGXL_ENABLE_OPENEXR=OFF",
    "-DJPEGXL_ENABLE_SKCMS=ON",
    "-DJPEGXL_BUNDLE_LIBPNG=OFF",
]

# License file in the archive -> path relative to the source or build directory.
LICENSES = {
    "LICENSE.libjxl": ("source", "LICENSE"),
    "LICENSE.brotli": ("build", "LICENSE.brotli"),
    "LICENSE.highway": ("build", "LICENSE.highway"),
    "LICENSE.skcms": ("build", "LICENSE.skcms"),
}


def run(cmd: list[str], cwd: Path | None = None) -> None:
    print(f"> {' '.join(cmd)}", flush=True)
    subprocess.run(cmd, cwd=cwd, check=True)


def output_of(cmd: list[str]) -> str:
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def checkout(source: Path) -> None:
    """Clones the pinned tag with its submodules and checks the commit."""
    if source.exists():
        shutil.rmtree(source)
    run(["git", "clone", "--depth", "1", "--branch", LIBJXL_TAG, LIBJXL_REPOSITORY, str(source)])
    head = output_of(["git", "-C", str(source), "rev-parse", "HEAD"]).strip()
    if head != LIBJXL_COMMIT:
        raise SystemExit(f"Tag {LIBJXL_TAG} points to {head}, expected {LIBJXL_COMMIT}")
    try:
        run(["git", "submodule", "update", "--init", "--depth", "1", *SUBMODULES], cwd=source)
    except subprocess.CalledProcessError:
        # Some servers refuse a shallow fetch of a specific commit; fetch the full history then.
        run(["git", "submodule", "update", "--init", *SUBMODULES], cwd=source)


def build(source: Path, build_dir: Path, install_dir: Path, platform_options: list[str],
          strip: bool = False) -> None:
    """Configures, builds and installs libjxl with the common and the platform options."""
    for directory in (build_dir, install_dir):
        if directory.exists():
            shutil.rmtree(directory)
    run(["cmake", "-S", str(source), "-B", str(build_dir), f"-DCMAKE_INSTALL_PREFIX={install_dir}",
         *COMMON_CMAKE_OPTIONS, *platform_options])
    run(["cmake", "--build", str(build_dir), "--config", "Release", "--parallel", str(os.cpu_count() or 4)])
    run(["cmake", "--install", str(build_dir), "--config", "Release", *(["--strip"] if strip else [])])


def copy_licenses(source: Path, build_dir: Path, licenses: Path) -> None:
    """Copies the licenses of libjxl and its bundled dependencies."""
    roots = {"source": source, "build": build_dir}
    for name, (root, relative) in LICENSES.items():
        shutil.copyfile(roots[root] / relative, licenses / name)


def pack(staging: Path, output: Path, archive_root: str, libraries: list[str]) -> Path:
    """Writes <archive_root>.tar.gz with the content of the staging directory,
    and its SHA-256 in the format of sha256sum next to it."""
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"{archive_root}.tar.gz"
    now = int(time.time())
    with tarfile.open(archive, "w:gz") as tar:
        for path in sorted(p for p in staging.rglob("*") if p.is_file()):
            info = tarfile.TarInfo(f"{archive_root}/{path.relative_to(staging).as_posix()}")
            data = path.read_bytes()
            info.size = len(data)
            info.mode = 0o755 if path.name in libraries else 0o644
            info.mtime = now
            tar.addfile(info, io.BytesIO(data))
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (output / f"{archive.name}.sha256").write_text(f"{digest}  {archive.name}\n", encoding="ascii", newline="\n")
    print(f"Wrote {archive} ({archive.stat().st_size:,} bytes), SHA-256 {digest}")
    return archive
