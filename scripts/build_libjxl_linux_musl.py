"""Build the libjxl shared libraries for musl-based Linux from source.

libjxl publishes no binaries for Linux with the musl C library (such as
Alpine Linux), so this script builds the pinned release with only the runtime
libraries: libjxl, libjxl_cms (with skcms) and libjxl_threads, plus the Brotli
libraries they need; Highway is linked statically. It runs in an Alpine 3.18
container (musl 1.2.4), so the libraries run on Alpine 3.18 or newer. The C++
runtime and libgcc are linked statically and their symbols are kept local, so
the libraries need nothing but the musl C library; minimal Alpine images have
no libstdc++. The libraries are stored under their SONAMEs with a RUNPATH of
$ORIGIN, so they find each other in any directory.

Every library is checked with readelf before it is packed: architecture,
SONAME, RUNPATH, dependencies, no glibc symbol versions and no exported
symbols of the C++ runtime. Finally the libraries are loaded, and libjxl must
report the pinned version.

Writes to the output directory:
  * libjxl-<version>-linux-musl-<arch>.tar.gz with lib/ and licenses/
  * the same name with .sha256, in the format of sha256sum

Runs on musl-based Linux on x86_64 or aarch64 only; it is used by
.github/workflows/libjxl-linux-musl.yml. The content of the archive is
checked in under natives/ (see natives/README.md). Checkout, build and packing
are shared with the other platforms in libjxl_source.py, which also lists the
common CMake options.

Usage:
  python build_libjxl_linux_musl.py [--work DIR] [--output DIR]
"""

import argparse
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

from libjxl_source import (LIBJXL_VERSION, ORIGIN, build, check_loading, checkout, copy_library, copy_licenses,
                           dynamic_entries, output_of, pack, run)
from tools_dir import PROJECT_DIR

# Architecture -> machine in the ELF header.
MACHINES = {
    "x86_64": "Advanced Micro Devices X86-64",
    "aarch64": "AArch64",
}

CMAKE_OPTIONS = [
    "-DCMAKE_INSTALL_LIBDIR=lib",
    # Link the C++ runtime and libgcc into every library and keep their
    # symbols local, so that the copies do not interpose each other.
    "-DCMAKE_SHARED_LINKER_FLAGS=-static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL",
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

# A line of readelf --dyn-syms --wide.
SYMBOL_LINE = re.compile(r"\s*\d+:\s+\S+\s+\S+\s+\S+\s+(?P<bind>\S+)\s+(?P<vis>\S+)(?:\s+\[[^\]]*\])?"
                         r"\s+(?P<ndx>\S+)\s+(?P<name>\S+)")


def musl_loader(arch: str) -> Path:
    """The musl dynamic loader, which is also the musl C library."""
    return Path(f"/lib/ld-musl-{arch}.so.1")


def runs_on_musl() -> bool:
    """Whether this process uses the musl C library, judged by its memory map."""
    try:
        maps = Path("/proc/self/maps").read_text(encoding="ascii", errors="replace")
    except OSError:
        return False
    return any(line.rsplit("/", 1)[-1].startswith("ld-musl-") for line in maps.splitlines() if "/" in line)


def musl_version(arch: str) -> str:
    """The version the musl loader prints when it is run without arguments."""
    result = subprocess.run([str(musl_loader(arch))], capture_output=True, text=True)
    match = re.search(r"Version\s+(\S+)", result.stderr)
    return f"musl {match.group(1)}" if match else "musl (unknown version)"


def exported_symbols(library: Path) -> set[str]:
    """The names of the symbols the library defines and exports, without versions."""
    symbols = output_of(["readelf", "--dyn-syms", "--wide", str(library)])
    found = set()
    for line in symbols.splitlines():
        # Num: Value Size Type Bind Vis Ndx Name; on aarch64, Vis may be followed by [VARIANT_PCS].
        match = SYMBOL_LINE.match(line)
        if not match or match["ndx"] == "UND" or match["bind"] not in ("GLOBAL", "WEAK"):
            continue
        if match["vis"] not in ("DEFAULT", "PROTECTED"):
            continue
        found.add(match["name"].split("@")[0])
    return found


def runtime_symbols(work: Path) -> set[str]:
    """The symbols the shared C++ runtime and libgcc of the build system
    export. Template code that a library instantiates itself, such as
    std::vector members, is not among them, and neither are the symbols that
    the linker puts into every shared library, such as _init and _fini."""
    symbols = set()
    for name in ("libstdc++.so.6", "libgcc_s.so.1"):
        path = Path(output_of(["g++", f"-print-file-name={name}"]).strip())
        if not path.is_absolute() or not path.exists():
            raise SystemExit(f"{name} of the C++ compiler not found")
        symbols |= exported_symbols(path.resolve())
    return symbols - linker_symbols(work)


def linker_symbols(work: Path) -> set[str]:
    """The symbols an empty shared library exports, built by the same compiler."""
    source = work / "empty.c"
    library = work / "libempty.so"
    source.write_text("", encoding="ascii")
    run(["gcc", "-shared", "-o", str(library), str(source)])
    return exported_symbols(library)


def check(library: Path, arch: str, runtime: set[str]) -> None:
    """Fails unless the library is a relocatable library for musl on the
    given architecture that needs only our libraries and the C library."""
    name = library.name
    header = output_of(["readelf", "--file-header", "--wide", str(library)])
    if not re.search(r"Class:\s+ELF64", header) or not re.search(rf"Machine:\s+{re.escape(MACHINES[arch])}", header):
        raise SystemExit(f"{name}: not a 64-bit {MACHINES[arch]} ELF file")

    if dynamic_entries(library, "SONAME") != [name]:
        raise SystemExit(f"{name}: unexpected SONAME {dynamic_entries(library, 'SONAME')}")

    libc = f"libc.musl-{arch}.so.1"
    for dependency in dynamic_entries(library, "NEEDED"):
        if dependency not in LIBRARIES and dependency != libc:
            raise SystemExit(f"{name}: unexpected dependency {dependency}")

    if dynamic_entries(library, "RUNPATH") != [ORIGIN] or dynamic_entries(library, "RPATH"):
        raise SystemExit(f"{name}: RUNPATH must be {ORIGIN} only, found RUNPATH "
                         f"{dynamic_entries(library, 'RUNPATH')} and RPATH {dynamic_entries(library, 'RPATH')}")

    versions = output_of(["readelf", "--version-info", "--wide", str(library)])
    if re.search(r"Name: GLIBC", versions):
        raise SystemExit(f"{name}: needs glibc symbol versions")

    exported = exported_symbols(library) & runtime
    if exported:
        raise SystemExit(f"{name}: exports {len(exported)} symbols of the C++ runtime, such as "
                         f"{', '.join(sorted(exported)[:5])}")
    print(f"Checked {name}: {arch}, SONAME, RUNPATH, dependencies and exported symbols OK", flush=True)


def collect(source: Path, build_dir: Path, install_dir: Path, staging: Path, arch: str) -> None:
    lib = staging / "lib"
    licenses = staging / "licenses"
    lib.mkdir(parents=True)
    licenses.mkdir()
    runtime = runtime_symbols(staging.parent)
    for name in LIBRARIES:
        target = lib / name
        copy_library(install_dir / "lib" / name, target)
        check(target, arch, runtime)
    check_loading(lib, musl_version(arch))
    copy_licenses(source, build_dir, licenses)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", type=Path, help="working directory for the sources and the build "
                        "(default: target/libjxl-linux-musl-<arch> in the project)")
    parser.add_argument("--output", type=Path, default=PROJECT_DIR / "dist" / "natives",
                        help="directory for the archive")
    args = parser.parse_args()
    arch = platform.machine()
    if not sys.platform.startswith("linux") or arch not in MACHINES or not runs_on_musl():
        raise SystemExit("This script runs on musl-based Linux on x86_64 or aarch64 only")

    archive_root = f"libjxl-{LIBJXL_VERSION}-linux-musl-{arch}"
    work = (args.work or PROJECT_DIR / "target" / f"libjxl-linux-musl-{arch}").resolve()
    source = work / "libjxl"
    build_dir = work / "build"
    install_dir = work / "install"
    staging = work / archive_root
    checkout(source)
    build(source, build_dir, install_dir, CMAKE_OPTIONS, strip=True)
    if staging.exists():
        shutil.rmtree(staging)
    collect(source, build_dir, install_dir, staging, arch)
    pack(staging, args.output.resolve(), archive_root, LIBRARIES)
    return 0


if __name__ == "__main__":
    sys.exit(main())
