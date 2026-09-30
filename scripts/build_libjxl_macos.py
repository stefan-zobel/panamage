"""Build the libjxl shared libraries for macOS arm64 from source.

libjxl publishes no macOS binaries, so this script builds the pinned release
with only the runtime libraries: libjxl, libjxl_cms (with skcms) and
libjxl_threads, plus the Brotli libraries they need; Highway is linked
statically. The libraries find each other through @rpath and an LC_RPATH of
@loader_path, so they work from any directory, and they run on macOS 11 or
newer. Every library is checked with otool and codesign before it is packed.

Writes to the output directory:
  * libjxl-<version>-macos-aarch64.tar.gz with lib/ and licenses/
  * the same name with .sha256, in the format of shasum

Runs on macOS arm64 only; it is used by .github/workflows/libjxl-macos.yml.
The content of the archive is checked in under natives/ (see natives/README.md).

Usage:
  python build_libjxl_macos.py [--work DIR] [--output DIR]
"""

import argparse
import hashlib
import io
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import time
from pathlib import Path

from tools_dir import PROJECT_DIR

LIBJXL_VERSION = "0.12.0"
LIBJXL_REPOSITORY = "https://github.com/libjxl/libjxl.git"
LIBJXL_TAG = f"v{LIBJXL_VERSION}"
LIBJXL_COMMIT = "a7a9c787341cf703dede03c2009fa460cae5e5df"
SUBMODULES = ["third_party/brotli", "third_party/highway", "third_party/skcms"]

DEPLOYMENT_TARGET = "11.0"
ARCHIVE_ROOT = f"libjxl-{LIBJXL_VERSION}-macos-aarch64"

CMAKE_OPTIONS = [
    "-DCMAKE_BUILD_TYPE=Release",
    "-DBUILD_SHARED_LIBS=ON",
    "-DBUILD_TESTING=OFF",
    "-DCMAKE_OSX_ARCHITECTURES=arm64",
    f"-DCMAKE_OSX_DEPLOYMENT_TARGET={DEPLOYMENT_TARGET}",
    "-DCMAKE_INSTALL_RPATH=@loader_path",
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

# File names are the install names (@rpath/<name>), in load order.
LIBRARIES = [
    "libbrotlicommon.1.dylib",
    "libbrotlidec.1.dylib",
    "libbrotlienc.1.dylib",
    "libjxl_cms.0.12.dylib",
    "libjxl.0.12.dylib",
    "libjxl_threads.0.12.dylib",
]

# License file in the archive -> path relative to the source or build directory.
LICENSES = {
    "LICENSE.libjxl": ("source", "LICENSE"),
    "LICENSE.brotli": ("build", "LICENSE.brotli"),
    "LICENSE.highway": ("build", "LICENSE.highway"),
    "LICENSE.skcms": ("build", "LICENSE.skcms"),
}

SYSTEM_LIBRARIES = {"/usr/lib/libc++.1.dylib", "/usr/lib/libSystem.B.dylib"}
LOADER_PATH = "@loader_path"


def run(cmd: list[str], cwd: Path | None = None) -> None:
    print(f"> {' '.join(cmd)}", flush=True)
    subprocess.run(cmd, cwd=cwd, check=True)


def output_of(cmd: list[str]) -> str:
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def checkout(source: Path) -> None:
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


def build(source: Path, build_dir: Path, install_dir: Path) -> None:
    for directory in (build_dir, install_dir):
        if directory.exists():
            shutil.rmtree(directory)
    run(["cmake", "-S", str(source), "-B", str(build_dir), f"-DCMAKE_INSTALL_PREFIX={install_dir}",
         *CMAKE_OPTIONS])
    run(["cmake", "--build", str(build_dir), "--config", "Release", "--parallel", str(os.cpu_count() or 4)])
    run(["cmake", "--install", str(build_dir), "--config", "Release"])


def rpaths(library: Path) -> list[str]:
    """The LC_RPATH entries of a Mach-O file."""
    commands = output_of(["otool", "-l", str(library)])
    return re.findall(r"cmd LC_RPATH\n\s+cmdsize \d+\n\s+path (\S+)", commands)


def fix_rpaths(library: Path) -> None:
    """Keeps @loader_path as the only LC_RPATH and signs the file again if it changed."""
    current = rpaths(library)
    changed = False
    for path in current:
        if path != LOADER_PATH:
            run(["install_name_tool", "-delete_rpath", path, str(library)])
            changed = True
    if LOADER_PATH not in current:
        run(["install_name_tool", "-add_rpath", LOADER_PATH, str(library)])
        changed = True
    if changed:
        run(["codesign", "--force", "--sign", "-", str(library)])


def check(library: Path) -> None:
    """Fails unless the library is a relocatable arm64 dylib for macOS 11 that needs only our libraries."""
    name = library.name
    install_name = output_of(["otool", "-D", str(library)]).splitlines()[-1].strip()
    if install_name != f"@rpath/{name}":
        raise SystemExit(f"{name}: unexpected install name {install_name}")

    for line in output_of(["otool", "-L", str(library)]).splitlines()[1:]:
        dependency = line.strip().split(" (")[0]
        bundled = dependency.startswith("@rpath/") and dependency.removeprefix("@rpath/") in LIBRARIES
        if not bundled and dependency not in SYSTEM_LIBRARIES:
            raise SystemExit(f"{name}: unexpected dependency {dependency}")

    if rpaths(library) != [LOADER_PATH]:
        raise SystemExit(f"{name}: LC_RPATH must be {LOADER_PATH} only, found {rpaths(library)}")

    architectures = output_of(["lipo", "-archs", str(library)]).split()
    if architectures != ["arm64"]:
        raise SystemExit(f"{name}: expected arm64 only, found {architectures}")

    commands = output_of(["otool", "-l", str(library)])
    minimum = re.search(r"cmd LC_BUILD_VERSION\n.*?\n\s+platform (\d+)\n\s+minos (\S+)", commands)
    if minimum is None or minimum.group(1) != "1" or minimum.group(2) != DEPLOYMENT_TARGET:
        raise SystemExit(f"{name}: expected platform macOS (1) and minos {DEPLOYMENT_TARGET}, "
                         f"found {minimum.groups() if minimum else 'no LC_BUILD_VERSION'}")

    run(["codesign", "--verify", "--strict", str(library)])
    print(f"Checked {name}: arm64, macOS {DEPLOYMENT_TARGET}+, dependencies and signature OK", flush=True)


def collect(source: Path, build_dir: Path, install_dir: Path, staging: Path) -> None:
    lib = staging / "lib"
    licenses = staging / "licenses"
    lib.mkdir(parents=True)
    licenses.mkdir()
    for name in LIBRARIES:
        installed = install_dir / "lib" / name
        if not installed.exists():
            raise SystemExit(f"{installed} was not built")
        # Store the real file under its install name, not the symbolic link.
        target = lib / name
        shutil.copyfile(installed.resolve(), target)
        fix_rpaths(target)
        check(target)
    roots = {"source": source, "build": build_dir}
    for name, (root, relative) in LICENSES.items():
        shutil.copyfile(roots[root] / relative, licenses / name)


def pack(staging: Path, output: Path) -> Path:
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"{ARCHIVE_ROOT}.tar.gz"
    now = int(time.time())
    with tarfile.open(archive, "w:gz") as tar:
        for path in sorted(p for p in staging.rglob("*") if p.is_file()):
            info = tarfile.TarInfo(f"{ARCHIVE_ROOT}/{path.relative_to(staging).as_posix()}")
            data = path.read_bytes()
            info.size = len(data)
            info.mode = 0o755 if path.suffix == ".dylib" else 0o644
            info.mtime = now
            tar.addfile(info, io.BytesIO(data))
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (output / f"{archive.name}.sha256").write_text(f"{digest}  {archive.name}\n", encoding="ascii", newline="\n")
    print(f"Wrote {archive} ({archive.stat().st_size:,} bytes), SHA-256 {digest}")
    return archive


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", type=Path, default=PROJECT_DIR / "target" / "libjxl-macos",
                        help="working directory for the sources and the build")
    parser.add_argument("--output", type=Path, default=PROJECT_DIR / "dist" / "natives",
                        help="directory for the archive")
    args = parser.parse_args()
    if sys.platform != "darwin" or platform.machine() != "arm64":
        raise SystemExit("This script runs on macOS arm64 only")

    work = args.work.resolve()
    source = work / "libjxl"
    build_dir = work / "build"
    install_dir = work / "install"
    staging = work / ARCHIVE_ROOT
    checkout(source)
    build(source, build_dir, install_dir)
    if staging.exists():
        shutil.rmtree(staging)
    collect(source, build_dir, install_dir, staging)
    pack(staging, args.output.resolve())
    return 0


if __name__ == "__main__":
    sys.exit(main())
