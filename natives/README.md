# Prebuilt native libraries

libjxl publishes no binaries for macOS, so the libraries for macOS are built
by this project and checked in here. The other platforms use the binaries of
the libjxl releases, downloaded by `scripts/fetch_tools.py`.

## libjxl-0.12.0-macos-aarch64

The libjxl 0.12.0 runtime libraries for macOS 11 or newer on Apple silicon:
libjxl, libjxl_cms (with skcms) and libjxl_threads, plus the Brotli libraries
they need; Highway is linked statically.

- Source: [libjxl](https://github.com/libjxl/libjxl) tag `v0.12.0`, commit
  `a7a9c787341cf703dede03c2009fa460cae5e5df`, with the submodules brotli,
  highway and skcms.
- Built by the workflow `.github/workflows/libjxl-macos.yml` (manual start)
  with `scripts/build_libjxl_macos.py`, which lists the CMake options.
- Checked by the script: install names `@rpath/<file name>`, no dependencies
  besides these libraries, `libSystem` and `libc++`, `@loader_path` as the only
  run path, arm64 only, minimum macOS 11.0, valid signatures.
- Workflow artifact `libjxl-0.12.0-macos-aarch64.tar.gz`, SHA-256
  `4ebce6468ee8027af5139e7d830ceca232e66980e8e0b46fcc5c5e0f27a99435`;
  the checked-in files are its content. `SHA256SUMS` lists the files
  (`sha256sum -c SHA256SUMS`).

The module `panamage-jxl-natives-macos-aarch64` packs them into its JAR.

To update, run the workflow, download its artifact and replace the directory
with the content of the archive.
