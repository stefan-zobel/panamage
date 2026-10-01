"""Build the libjxl shared libraries for Linux aarch64 from source.

libjxl publishes no binaries for Linux on 64-bit ARM, so this script builds
the pinned release with only the runtime libraries: libjxl, libjxl_cms (with
skcms) and libjxl_threads, plus the Brotli libraries they need; Highway is
linked statically. It runs in the manylinux_2_28 container (AlmaLinux 8), so
the libraries need glibc 2.28 and the C++ runtime of that system or newer.
The libraries are stored under their SONAMEs with a RUNPATH of $ORIGIN, so
they find each other in any directory.

Every library is checked with readelf before it is packed: architecture,
SONAME, RUNPATH, dependencies and the highest symbol versions of glibc and
the C++ runtime. Finally the libraries are loaded, and libjxl must report the
pinned version.

Writes to the output directory:
  * libjxl-<version>-linux-aarch64.tar.gz with lib/ and licenses/
  * the same name with .sha256, in the format of sha256sum

Runs on Linux aarch64 only; it is used by
.github/workflows/libjxl-linux-aarch64.yml. The content of the archive is
checked in under natives/ (see natives/README.md). Checkout, build and packing
are shared with the other platforms in libjxl_source.py, which also lists the
common CMake options.

Usage:
  python build_libjxl_linux_aarch64.py [--work DIR] [--output DIR]
"""

import argparse
import platform
import re
import shutil
import sys
from pathlib import Path

from libjxl_source import (LIBJXL_VERSION, ORIGIN, build, check_loading, checkout, copy_library, copy_licenses,
                           dynamic_entries, output_of, pack)
from tools_dir import PROJECT_DIR

ARCHIVE_ROOT = f"libjxl-{LIBJXL_VERSION}-linux-aarch64"

CMAKE_OPTIONS = [
    # GNUInstallDirs would choose lib64 on AlmaLinux.
    "-DCMAKE_INSTALL_LIBDIR=lib",
]

# File names are the SONAMEs, in load order.
LIBRARIES = [
    "libbrotlicommon.so.1",
    "libbrotlidec.so.1",
    "libbrotlienc.so.1",
    "libjxl_cms.so.0.12",
    "libjxl.so.0.12",
    "libjxl_threads.so.0.12",
]

SYSTEM_LIBRARIES = {
    "ld-linux-aarch64.so.1",
    "libc.so.6",
    "libdl.so.2",
    "libgcc_s.so.1",
    "libm.so.6",
    "libpthread.so.0",
    "libstdc++.so.6",
}

# The highest symbol versions the libraries may need: glibc 2.28 and the C++
# runtime of GCC 8, as in AlmaLinux 8 and RHEL 8.
MAXIMUM_VERSIONS = {
    "GLIBC": (2, 28),
    "GLIBCXX": (3, 4, 25),
    "CXXABI": (1, 3, 11),
}

def check(library: Path) -> None:
    """Fails unless the library is a relocatable aarch64 library for glibc 2.28 that needs only our libraries."""
    name = library.name
    header = output_of(["readelf", "--file-header", "--wide", str(library)])
    if not re.search(r"Class:\s+ELF64", header) or not re.search(r"Machine:\s+AArch64", header):
        raise SystemExit(f"{name}: not a 64-bit AArch64 ELF file")

    if dynamic_entries(library, "SONAME") != [name]:
        raise SystemExit(f"{name}: unexpected SONAME {dynamic_entries(library, 'SONAME')}")

    for dependency in dynamic_entries(library, "NEEDED"):
        if dependency not in LIBRARIES and dependency not in SYSTEM_LIBRARIES:
            raise SystemExit(f"{name}: unexpected dependency {dependency}")

    if dynamic_entries(library, "RUNPATH") != [ORIGIN] or dynamic_entries(library, "RPATH"):
        raise SystemExit(f"{name}: RUNPATH must be {ORIGIN} only, found RUNPATH "
                         f"{dynamic_entries(library, 'RUNPATH')} and RPATH {dynamic_entries(library, 'RPATH')}")

    versions = output_of(["readelf", "--version-info", "--wide", str(library)])
    if "GLIBC_PRIVATE" in versions:
        raise SystemExit(f"{name}: needs private glibc symbols")
    for prefix, maximum in MAXIMUM_VERSIONS.items():
        needed = [tuple(int(part) for part in version.split("."))
                  for version in re.findall(rf"Name: {prefix}_(\d+(?:\.\d+)*)\b", versions)]
        highest = max(needed, default=())
        if highest > maximum:
            raise SystemExit(f"{name}: needs {prefix} {'.'.join(map(str, highest))}, "
                             f"at most {'.'.join(map(str, maximum))} is allowed")
    print(f"Checked {name}: AArch64, SONAME, RUNPATH, dependencies and symbol versions OK", flush=True)


def collect(source: Path, build_dir: Path, install_dir: Path, staging: Path) -> None:
    lib = staging / "lib"
    licenses = staging / "licenses"
    lib.mkdir(parents=True)
    licenses.mkdir()
    for name in LIBRARIES:
        target = lib / name
        copy_library(install_dir / "lib" / name, target)
        check(target)
    check_loading(lib, " ".join(platform.libc_ver()))
    copy_licenses(source, build_dir, licenses)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", type=Path, default=PROJECT_DIR / "target" / "libjxl-linux-aarch64",
                        help="working directory for the sources and the build")
    parser.add_argument("--output", type=Path, default=PROJECT_DIR / "dist" / "natives",
                        help="directory for the archive")
    args = parser.parse_args()
    if not sys.platform.startswith("linux") or platform.machine() != "aarch64":
        raise SystemExit("This script runs on Linux aarch64 only")

    work = args.work.resolve()
    source = work / "libjxl"
    build_dir = work / "build"
    install_dir = work / "install"
    staging = work / ARCHIVE_ROOT
    checkout(source)
    build(source, build_dir, install_dir, CMAKE_OPTIONS, strip=True)
    if staging.exists():
        shutil.rmtree(staging)
    collect(source, build_dir, install_dir, staging)
    pack(staging, args.output.resolve(), ARCHIVE_ROOT, LIBRARIES)
    return 0


if __name__ == "__main__":
    sys.exit(main())
