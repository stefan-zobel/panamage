"""Build the libjxl DLLs for Windows x64 from source.

The DLLs of the libjxl releases use the C++ runtime of the process
(msvcp140.dll and vcruntime140.dll). The JVM loads its own copy of these
DLLs from its bin directory, and libjxl 0.12.0 needs version 14.40 or newer
(the std::mutex change of Visual Studio 2022 17.10); with an older copy,
such as the one of JDK 21.0.3, JxlThreadParallelRunnerCreate crashes.

This script builds the pinned release with the static C and C++ runtime
(/MT), so the DLLs depend on nothing but KERNEL32.dll and each other, and
work with any C++ runtime in the process. It builds only the runtime
libraries: libjxl, libjxl_cms (with skcms) and libjxl_threads, plus the
Brotli libraries they need; Highway is linked statically. The file names are
those of the libjxl release, so the archive can replace it.

Every DLL is checked with dumpbin before it is packed: x64, dependencies and,
for libjxl and libjxl_threads, the exported entry points. Finally the DLLs
are loaded in a new process, libjxl must report the pinned version, and a
libjxl_threads runner is created and destroyed.

Writes to the output directory:
  * libjxl-<version>-windows-x86_64.tar.gz with bin/ and licenses/, the
    layout of the libjxl release (usable as -Djxl.native.dir after unpacking)
  * the same name with .sha256, in the format of sha256sum

Needs Visual Studio 2022 or newer with the C++ x64 tools and the C++ Clang
tools (clang-cl), CMake and Ninja (all part of the Visual Studio installer),
and git. Checkout, build and packing are shared with the other platforms in
libjxl_source.py, which also lists the common CMake options. The workflow
.github/workflows/libjxl-windows.yml runs this script on a GitHub runner.

Usage:
  python build_libjxl_windows.py [--work DIR] [--output DIR]
"""

import argparse
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

from libjxl_source import EXPECTED_VERSION, LIBJXL_VERSION, build, checkout, copy_licenses, output_of, pack
from tools_dir import PROJECT_DIR

ARCHIVE_ROOT = f"libjxl-{LIBJXL_VERSION}-windows-x86_64"

VSWHERE = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / \
    "Microsoft Visual Studio" / "Installer" / "vswhere.exe"
VC_TOOLS_COMPONENT = "Microsoft.VisualStudio.Component.VC.Tools.x86.x64"

CMAKE_OPTIONS = [
    "-G", "Ninja",
    "-DCMAKE_C_COMPILER=clang-cl",
    "-DCMAKE_CXX_COMPILER=clang-cl",
    # The static C and C++ runtime (/MT) in every DLL.
    "-DCMAKE_POLICY_DEFAULT_CMP0091=NEW",
    "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded",
]

# File names of the libjxl release, in load order.
LIBRARIES = [
    "brotlicommon.dll",
    "brotlidec.dll",
    "brotlienc.dll",
    "jxl_cms.dll",
    "jxl.dll",
    "jxl_threads.dll",
]

SYSTEM_LIBRARIES = {"kernel32.dll"}

# Entry points that panamage-jxl resolves first; their absence means a wrong build.
EXPORTS = {
    "jxl.dll": ["JxlDecoderVersion", "JxlEncoderVersion", "JxlDecoderCreate", "JxlEncoderCreate"],
    "jxl_threads.dll": ["JxlThreadParallelRunner", "JxlThreadParallelRunnerCreate"],
}


def visual_studio() -> Path:
    """The installation directory of the newest Visual Studio with the C++ x64 tools."""
    if not VSWHERE.is_file():
        raise SystemExit(f"vswhere not found at {VSWHERE}; install Visual Studio 2022 with the C++ tools")
    path = output_of([str(VSWHERE), "-latest", "-products", "*", "-requires", VC_TOOLS_COMPONENT,
                      "-property", "installationPath"]).strip()
    if not path:
        raise SystemExit("No Visual Studio with the C++ x64 tools found")
    return Path(path)


def developer_environment(vs: Path) -> dict[str, str]:
    """The environment of a Visual Studio x64 developer command prompt."""
    vcvars = vs / "VC" / "Auxiliary" / "Build" / "vcvars64.bat"
    if not vcvars.is_file():
        raise SystemExit(f"{vcvars} not found")
    result = subprocess.run(f'cmd /d /s /c ""{vcvars}" >nul && set"', capture_output=True, text=True,
                            check=True)
    environment = {}
    for line in result.stdout.splitlines():
        name, sep, value = line.partition("=")
        if sep and name:
            environment[name] = value
    # clang-cl is part of the C++ Clang tools, which vcvars does not put on the PATH. CMake and
    # Ninja of Visual Studio go first as well, ahead of other installations on the PATH.
    llvm = vs / "VC" / "Tools" / "Llvm" / "x64" / "bin"
    if not (llvm / "clang-cl.exe").is_file():
        raise SystemExit(f"clang-cl not found in {llvm}; install the C++ Clang tools for Windows")
    cmake_tools = vs / "Common7" / "IDE" / "CommonExtensions" / "Microsoft" / "CMake"
    first = [llvm, cmake_tools / "CMake" / "bin", cmake_tools / "Ninja"]
    environment["PATH"] = os.pathsep.join([*(str(p) for p in first if p.is_dir()), environment["PATH"]])
    return environment


def require_tools(vs: Path) -> None:
    for tool in ("cmake", "ninja", "clang-cl", "dumpbin", "git"):
        if shutil.which(tool) is None:
            raise SystemExit(f"{tool} not found on the PATH of the developer environment")
    print(f"Using {shutil.which('cmake')}, {shutil.which('ninja')}, {shutil.which('clang-cl')}", flush=True)
    # The versions go into natives/README.md.
    vs_version = output_of([str(VSWHERE), "-path", str(vs), "-property", "catalog_productDisplayVersion"]).strip()
    clang_version = output_of(["clang-cl", "--version"]).splitlines()[0].strip()
    print(f"Visual Studio {vs_version}, {clang_version}", flush=True)


def check(library: Path) -> None:
    """Fails unless the DLL is x64 and needs only the bundled DLLs and KERNEL32."""
    name = library.name
    headers = output_of(["dumpbin", "/nologo", "/headers", str(library)])
    if "File Type: DLL" not in headers or not re.search(r"8664 machine \(x64\)", headers):
        raise SystemExit(f"{name}: not an x64 DLL")

    dependents = output_of(["dumpbin", "/nologo", "/dependents", str(library)])
    names = [line.strip() for line in dependents.splitlines() if line.strip().lower().endswith(".dll")
             and not line.strip().lower().startswith("dump of")]
    names = [n for n in names if n.lower() != name.lower()]
    bundled = {n.lower() for n in LIBRARIES}
    unexpected = [n for n in names if n.lower() not in bundled | SYSTEM_LIBRARIES]
    if unexpected:
        raise SystemExit(f"{name}: unexpected dependencies {unexpected}")

    if name in EXPORTS:
        exports = output_of(["dumpbin", "/nologo", "/exports", str(library)])
        exported = set(re.findall(r"^\s+\d+\s+[0-9A-F]+\s+[0-9A-F]{8}\s+(\w+)", exports, re.MULTILINE))
        missing = [symbol for symbol in EXPORTS[name] if symbol not in exported]
        if missing:
            raise SystemExit(f"{name}: missing exports {missing}")
    print(f"Checked {name}: x64, dependencies {names or 'none'}", flush=True)


def check_loading(bin_dir: Path) -> None:
    """Loads the DLLs in a new process, checks the version and creates a thread runner."""
    script = (
        "import ctypes, os\n"
        f"os.add_dll_directory({str(bin_dir)!r})\n"
        f"jxl = ctypes.CDLL({str(bin_dir / 'jxl.dll')!r})\n"
        f"threads = ctypes.CDLL({str(bin_dir / 'jxl_threads.dll')!r})\n"
        "jxl.JxlDecoderVersion.restype = ctypes.c_uint32\n"
        "threads.JxlThreadParallelRunnerCreate.restype = ctypes.c_void_p\n"
        "threads.JxlThreadParallelRunnerCreate.argtypes = [ctypes.c_void_p, ctypes.c_size_t]\n"
        "threads.JxlThreadParallelRunnerDestroy.argtypes = [ctypes.c_void_p]\n"
        "runner = threads.JxlThreadParallelRunnerCreate(None, 4)\n"
        "if not runner:\n"
        "    raise SystemExit('JxlThreadParallelRunnerCreate failed')\n"
        "threads.JxlThreadParallelRunnerDestroy(runner)\n"
        "print(jxl.JxlDecoderVersion())\n"
    )
    version = subprocess.run([sys.executable, "-c", script], check=True, capture_output=True,
                             text=True).stdout.strip()
    if version != str(EXPECTED_VERSION):
        raise SystemExit(f"libjxl reports version {version}, expected {EXPECTED_VERSION}")
    print(f"Loaded the DLLs: libjxl version {version}, thread runner OK", flush=True)


def collect(source: Path, build_dir: Path, install_dir: Path, staging: Path) -> None:
    bin_dir = staging / "bin"
    licenses = staging / "licenses"
    bin_dir.mkdir(parents=True)
    licenses.mkdir()
    for name in LIBRARIES:
        installed = install_dir / "bin" / name
        if not installed.exists():
            raise SystemExit(f"{installed} was not built")
        target = bin_dir / name
        shutil.copyfile(installed, target)
        check(target)
    check_loading(bin_dir)
    copy_licenses(source, build_dir, licenses)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", type=Path, default=PROJECT_DIR / "target" / "libjxl-windows",
                        help="working directory for the sources and the build")
    parser.add_argument("--output", type=Path, default=PROJECT_DIR / "dist" / "natives",
                        help="directory for the archive")
    args = parser.parse_args()
    if sys.platform != "win32" or platform.machine() != "AMD64":
        raise SystemExit("This script runs on Windows x64 only")

    vs = visual_studio()
    os.environ.update(developer_environment(vs))
    require_tools(vs)

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
