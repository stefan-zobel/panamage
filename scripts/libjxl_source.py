"""Checkout, build and packing of libjxl from source, shared by the scripts
that build the native libraries for platforms without suitable libjxl
binaries (build_libjxl_macos.py, build_libjxl_linux_aarch64.py,
build_libjxl_linux_musl.py, build_libjxl_windows.py), plus the ELF helpers of
the Linux scripts.

The pinned release is built with only the runtime libraries: libjxl,
libjxl_cms (with skcms) and libjxl_threads, plus the Brotli libraries they
need; Highway is linked statically.
"""

import hashlib
import io
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import time
from pathlib import Path

LIBJXL_VERSION = "0.12.0"
LIBJXL_REPOSITORY = "https://github.com/libjxl/libjxl.git"
LIBJXL_TAG = f"v{LIBJXL_VERSION}"
LIBJXL_COMMIT = "a7a9c787341cf703dede03c2009fa460cae5e5df"
SUBMODULES = ["third_party/brotli", "third_party/highway", "third_party/skcms"]

# JxlDecoderVersion() returns major * 1000000 + minor * 1000 + patch.
EXPECTED_VERSION = sum(int(part) * factor for part, factor in zip(LIBJXL_VERSION.split("."), (1000000, 1000, 1)))

# The only run path of the Linux libraries, so they find each other in any directory.
ORIGIN = "$ORIGIN"

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


def remove_tree(path: Path) -> None:
    """Deletes a directory tree, also read-only files such as the pack files of git on Windows."""
    def make_writable(function, name, _):
        os.chmod(name, stat.S_IWRITE)
        function(name)
    shutil.rmtree(path, onexc=make_writable)


def checkout(source: Path) -> None:
    """Clones the pinned tag with its submodules and checks the commit."""
    if source.exists():
        remove_tree(source)
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
            remove_tree(directory)
    run(["cmake", "-S", str(source), "-B", str(build_dir), f"-DCMAKE_INSTALL_PREFIX={install_dir}",
         *COMMON_CMAKE_OPTIONS, *platform_options])
    run(["cmake", "--build", str(build_dir), "--config", "Release", "--parallel", str(os.cpu_count() or 4)])
    run(["cmake", "--install", str(build_dir), "--config", "Release", *(["--strip"] if strip else [])])


def dynamic_entries(library: Path, tag: str) -> list[str]:
    """The values of the entries of the dynamic section with the given tag, such as NEEDED."""
    dynamic = output_of(["readelf", "--dynamic", "--wide", str(library)])
    return re.findall(rf"\({tag}\)\s+[^\[]*\[([^\]]*)\]", dynamic)


def copy_library(installed: Path, target: Path) -> None:
    """Copies an installed Linux library under its SONAME, the real file and
    not the symbolic link, and sets its RUNPATH to $ORIGIN."""
    if not installed.exists():
        raise SystemExit(f"{installed} was not built")
    shutil.copyfile(installed.resolve(), target)
    run(["patchelf", "--set-rpath", ORIGIN, str(target)])


def check_loading(lib: Path, libc: str) -> None:
    """Loads libjxl and libjxl_threads in a new process, which finds their
    dependencies through the RUNPATH, and checks the version libjxl reports;
    libc describes the C library for the log."""
    script = (
        "import ctypes, sys\n"
        f"jxl = ctypes.CDLL({str(lib / 'libjxl.so.0.12')!r})\n"
        f"ctypes.CDLL({str(lib / 'libjxl_threads.so.0.12')!r})\n"
        "jxl.JxlDecoderVersion.restype = ctypes.c_uint32\n"
        "print(jxl.JxlDecoderVersion())\n"
    )
    version = subprocess.run([sys.executable, "-c", script], check=True, capture_output=True,
                             text=True).stdout.strip()
    if version != str(EXPECTED_VERSION):
        raise SystemExit(f"libjxl reports version {version}, expected {EXPECTED_VERSION}")
    print(f"Loaded the libraries with {libc}: libjxl version {version}", flush=True)


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
