# Prebuilt native libraries

libjxl publishes no binaries for macOS, for Linux on aarch64 and for
musl-based Linux, so the libraries for these platforms are built from source
by this project and checked in here. The DLLs of the libjxl release for
Windows use the C++ runtime of the process and need a newer version than some
JDKs ship, so the DLLs for Windows are built here as well, with the static
runtime. Linux on x86_64 uses the binaries of the libjxl release, downloaded
by `scripts/fetch_tools.py`. The build scripts share the checkout, the common
CMake options and the packing in `scripts/libjxl_source.py`.

## libjxl-0.12.0-macos-aarch64

The libjxl 0.12.0 runtime libraries for macOS 11 or newer on Apple silicon:
libjxl, libjxl_cms (with skcms) and libjxl_threads, plus the Brotli libraries
they need; Highway is linked statically.

- Source: [libjxl](https://github.com/libjxl/libjxl) tag `v0.12.0`, commit
  `a7a9c787341cf703dede03c2009fa460cae5e5df`, with the submodules brotli,
  highway and skcms.
- Built by the workflow `.github/workflows/libjxl-macos.yml` (manual start)
  with `scripts/build_libjxl_macos.py`; the CMake options are listed there and
  in `scripts/libjxl_source.py`.
- Checked by the script: install names `@rpath/<file name>`, no dependencies
  besides these libraries, `libSystem` and `libc++`, `@loader_path` as the only
  run path, arm64 only, minimum macOS 11.0, valid signatures.
- Workflow artifact `libjxl-0.12.0-macos-aarch64.tar.gz`, SHA-256
  `4ebce6468ee8027af5139e7d830ceca232e66980e8e0b46fcc5c5e0f27a99435`;
  the checked-in files are its content. `SHA256SUMS` lists the files
  (`sha256sum -c SHA256SUMS`).

The module `panamage-jxl-natives-macos-aarch64` packs them into its JAR.

## libjxl-0.12.0-linux-aarch64

The libjxl 0.12.0 runtime libraries for Linux on aarch64 (64-bit ARM) with
glibc 2.28 or newer: libjxl, libjxl_cms (with skcms) and libjxl_threads, plus
the Brotli libraries they need; Highway is linked statically. The files are
named after their SONAMEs and stripped.

- Source: the same libjxl commit and submodules as for macOS.
- Built by the workflow `.github/workflows/libjxl-linux-aarch64.yml` (manual
  start) with `scripts/build_libjxl_linux_aarch64.py` in the container
  `quay.io/pypa/manylinux_2_28_aarch64` 2026.09.30-1 (AlmaLinux 8), pinned by
  its digest in the workflow; the CMake options are listed in the script and
  in `scripts/libjxl_source.py`.
- Checked by the script: AArch64 ELF files, SONAME equal to the file name,
  `$ORIGIN` as the only RUNPATH, no dependencies besides these libraries,
  glibc and the C++ runtime, symbol versions of at most GLIBC 2.28,
  GLIBCXX 3.4.25 and CXXABI 1.3.11 (the libraries need GLIBC 2.27 and
  GLIBCXX 3.4.22), and libjxl loads in the container and reports version
  0.12.0.
- `SHA256SUMS` lists the files (`sha256sum -c SHA256SUMS`).

The module `panamage-jxl-natives-linux-aarch64` packs them into its JAR.

## libjxl-0.12.0-linux-musl-x86_64

The libjxl 0.12.0 runtime libraries for Linux on x86_64 with the musl C
library 1.2.4 or newer, such as Alpine Linux 3.18 or newer: libjxl,
libjxl_cms (with skcms) and libjxl_threads, plus the Brotli libraries they
need; Highway is linked statically. The C++ runtime and libgcc are linked
statically as well, with their symbols kept local, so the libraries need
nothing but the musl C library; minimal Alpine images have no libstdc++. The
files are named after their SONAMEs and stripped.

- Source: the same libjxl commit and submodules as for macOS.
- Built by the workflow `.github/workflows/libjxl-linux-musl.yml` (manual
  start) with `scripts/build_libjxl_linux_musl.py` in the container
  `alpine:3.18.12` (musl 1.2.4, GCC 12.2), pinned by its digest in the
  workflow; the CMake options are listed in the script and in
  `scripts/libjxl_source.py`.
- Checked by the script: x86-64 ELF files, SONAME equal to the file name,
  `$ORIGIN` as the only RUNPATH, no dependencies besides these libraries and
  `libc.musl-x86_64.so.1`, no glibc symbol versions, no exported symbols of
  the C++ runtime or libgcc, and libjxl loads in the container and reports
  version 0.12.0.
- Workflow artifact `libjxl-0.12.0-linux-musl-x86_64.tar.gz`, SHA-256
  `6a97aeb70ef09a588d7eb72f864e9e1f3bd74a42d37c933d7a0fa1b0af2a925d`
  (run 36873319774); the checked-in files are its content. `SHA256SUMS` lists
  the files (`sha256sum -c SHA256SUMS`).

The module `panamage-jxl-natives-linux-musl-x86_64` packs them into its JAR.

## libjxl-0.12.0-linux-musl-aarch64

The libjxl 0.12.0 runtime libraries for Linux on aarch64 (64-bit ARM) with
the musl C library 1.2.4 or newer, such as Alpine Linux 3.18 or newer, built
like the libraries for x86_64: the C++ runtime and libgcc are linked
statically with their symbols kept local, so the libraries need nothing but
the musl C library. The files are named after their SONAMEs and stripped.

- Source: the same libjxl commit and submodules as for macOS.
- Built by the same workflow and script as for x86_64, on an arm64 runner in
  the same `alpine:3.18.12` image (musl 1.2.4, GCC 12.2).
- Checked by the script: AArch64 ELF files, SONAME equal to the file name,
  `$ORIGIN` as the only RUNPATH, no dependencies besides these libraries and
  `libc.musl-aarch64.so.1`, no glibc symbol versions, no exported symbols of
  the C++ runtime or libgcc, and libjxl loads in the container and reports
  version 0.12.0.
- Workflow artifact `libjxl-0.12.0-linux-musl-aarch64.tar.gz`, SHA-256
  `da140482afb3bb2765c7ba1d1b866059ac91d92b0997cd21fbf2d24a2b108fd6`
  (run 36879483999); the checked-in files are its content. `SHA256SUMS` lists
  the files (`sha256sum -c SHA256SUMS`).

The module `panamage-jxl-natives-linux-musl-aarch64` packs them into its JAR.

## libjxl-0.12.0-windows-x86_64

The libjxl 0.12.0 DLLs for Windows 10 or newer on x86_64: libjxl, libjxl_cms
(with skcms) and libjxl_threads, plus the Brotli DLLs they need; Highway is
linked statically. The file names are those of the libjxl release.

The DLLs of the libjxl release use the Microsoft Visual C++ runtime of the
process (`msvcp140.dll`, `vcruntime140.dll`), which the JVM loads from its
`bin` directory, and need version 14.40 or newer; with an older copy, such as
the one of JDK 21.0.3 (14.36), `JxlThreadParallelRunnerCreate` crashes. These
DLLs are built with the static C and C++ runtime (`/MT`) instead, so they need
nothing but `KERNEL32.dll` and each other.

- Source: the same libjxl commit and submodules as for macOS.
- Built by the workflow `.github/workflows/libjxl-windows.yml` (manual start)
  with `scripts/build_libjxl_windows.py` on the runner `windows-2025` with
  Visual Studio 18.10.2, clang-cl 22.1.3 and Ninja; the CMake options are
  listed in the script and in `scripts/libjxl_source.py`.
- Checked by the script: x64 DLLs, no dependencies besides these DLLs and
  `KERNEL32.dll`, the entry points panamage-jxl resolves first are exported,
  and the DLLs load in a new process, libjxl reports version 0.12.0 and a
  thread runner can be created.
- Workflow artifact `libjxl-0.12.0-windows-x86_64.tar.gz`, SHA-256
  `09651acb9b717a024dd0375033798d367798a5dcf34a9052bdd7ddbe2017d493`
  (run 37026027257); the checked-in files are its content. `SHA256SUMS` lists
  the files (`sha256sum -c SHA256SUMS`).

The module `panamage-jxl-natives-windows-x86_64` packs them into its JAR.

To update the libraries of a platform, run its workflow, download the artifact
and replace the directory with the content of the archive.
