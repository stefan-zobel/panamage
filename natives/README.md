# Prebuilt native libraries

libjxl publishes no binaries for macOS and for Linux on aarch64, so the
libraries for these platforms are built from source by this project and
checked in here. The other platforms use the binaries of the libjxl releases,
downloaded by `scripts/fetch_tools.py`. The build scripts share the checkout,
the common CMake options and the packing in `scripts/libjxl_source.py`.

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

To update the libraries of a platform, run its workflow, download the artifact
and replace the directory with the content of the archive.
