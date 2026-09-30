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
Checkout, build and packing are shared with the other platforms in
libjxl_source.py, which also lists the common CMake options.

Usage:
  python build_libjxl_macos.py [--work DIR] [--output DIR]
"""

import argparse
import platform
import re
import shutil
import sys
from pathlib import Path

from libjxl_source import LIBJXL_VERSION, build, checkout, copy_licenses, output_of, pack, run
from tools_dir import PROJECT_DIR

DEPLOYMENT_TARGET = "11.0"
ARCHIVE_ROOT = f"libjxl-{LIBJXL_VERSION}-macos-aarch64"

CMAKE_OPTIONS = [
    "-DCMAKE_OSX_ARCHITECTURES=arm64",
    f"-DCMAKE_OSX_DEPLOYMENT_TARGET={DEPLOYMENT_TARGET}",
    "-DCMAKE_INSTALL_RPATH=@loader_path",
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

SYSTEM_LIBRARIES = {"/usr/lib/libc++.1.dylib", "/usr/lib/libSystem.B.dylib"}
LOADER_PATH = "@loader_path"


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
    copy_licenses(source, build_dir, licenses)


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
    build(source, build_dir, install_dir, CMAKE_OPTIONS)
    if staging.exists():
        shutil.rmtree(staging)
    collect(source, build_dir, install_dir, staging)
    pack(staging, args.output.resolve(), ARCHIVE_ROOT, LIBRARIES)
    return 0


if __name__ == "__main__":
    sys.exit(main())
