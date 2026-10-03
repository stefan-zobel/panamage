"""Regenerate the jextract bindings for libjxl used by the panamage-jxl module.

The generated sources are checked in. Run this script after changing the
libjxl version, the jextract version or the symbol list.

  python jextract_jxl.py                   regenerate the bindings
  python jextract_jxl.py --update-symbols  also rebuild jxl-symbols.txt from the headers

jextract and libjxl are taken from the tools directory of fetch_tools.py
(--tools, the PANAMAGE_TOOLS_DIR environment variable or .tools in the
project); --jextract-home and --jxl-home override single tools.
"""

import argparse
import ntpath
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from tools_dir import DEFAULT_TOOLS_DIR, ENVIRONMENT_VARIABLE, PROJECT_DIR, tools_dir

SCRIPTS_DIR = Path(__file__).resolve().parent
UMBRELLA_HEADER = SCRIPTS_DIR / "jxl_api.h"
SYMBOLS_FILE = SCRIPTS_DIR / "jxl-symbols.txt"

OUTPUT_DIR = PROJECT_DIR / "panamage-jxl" / "src" / "main" / "java"
TARGET_PACKAGE = "panamage.jxl.ffi"
HEADER_CLASS = "Jxl"

# The generated bindings do not load libraries themselves; the symbol lookup
# is delegated to the hand-written loader, which finds bundled, configured or
# system libraries.
SYMBOL_LOOKUP_PATTERN = re.compile(r"static final SymbolLookup SYMBOL_LOOKUP = [^;]+;")
SYMBOL_LOOKUP_REPLACEMENT = (
    "static final SymbolLookup SYMBOL_LOOKUP = panamage.jxl.internal.NativeLibraries.lookup();")

# SymbolLookup.findOrThrow exists since Java 23; the bindings are compiled for
# Java 22, so they use find(...).orElseThrow(), which throws the same exception.
FIND_OR_THROW_PATTERN = re.compile(r"SYMBOL_LOOKUP\.findOrThrow\((\"\w+\")\)")
FIND_OR_THROW_REPLACEMENT = r"SYMBOL_LOOKUP.find(\1).orElseThrow()"

# jextract types the C "long" and "long double" layouts for the platform it runs
# on (32-bit long on Windows, 64-bit on Linux), which makes the shared class fail
# to initialize elsewhere. libjxl's API does not use these types, so the unused
# constants are declared with the general ValueLayout type instead.
PLATFORM_DEPENDENT_LAYOUTS = {
    "C_LONG": "long",
    "C_LONG_DOUBLE": "long double",
}

JEXTRACT_DIR = "jextract-25"
LIBJXL_DIR = "libjxl-0.12.0"


def jextract_executable(jextract_home: Path) -> Path:
    name = "jextract.bat" if os.name == "nt" else "jextract"
    exe = jextract_home / "bin" / name
    if not exe.is_file():
        raise SystemExit(f"jextract not found at {exe}")
    return exe


def run(cmd: list[str]) -> str:
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        sys.stderr.write(result.stdout + result.stderr)
        raise SystemExit(f"Command failed with exit code {result.returncode}: {cmd[0]}")
    return result.stdout + result.stderr


def jextract_version(exe: Path) -> str:
    first_line = run([str(exe), "--version"]).splitlines()[0]
    return first_line.removeprefix("jextract").strip()


def libjxl_version_parts(include_dir: Path) -> tuple[int, int, int]:
    text = (include_dir / "jxl" / "version.h").read_text(encoding="utf-8")
    parts = []
    for part in ("MAJOR", "MINOR", "PATCH"):
        match = re.search(rf"#define JPEGXL_{part}_VERSION (\d+)", text)
        if match is None:
            raise SystemExit(f"JPEGXL_{part}_VERSION not found in version.h")
        parts.append(int(match.group(1)))
    return parts[0], parts[1], parts[2]


def libjxl_version(include_dir: Path) -> str:
    return ".".join(str(part) for part in libjxl_version_parts(include_dir))


def update_symbols(exe: Path, include_dir: Path) -> None:
    """Rebuild the symbol list from all declarations found in the libjxl headers."""
    with tempfile.TemporaryDirectory() as tmp:
        dump = Path(tmp) / "includes.txt"
        run([str(exe), "-I", str(include_dir), "--dump-includes", str(dump),
             str(UMBRELLA_HEADER)])
        lines = dump.read_text(encoding="utf-8").splitlines()

    jxl_dir = str(include_dir / "jxl").lower()
    by_header: dict[str, list[str]] = {}
    for line in lines:
        if not line.startswith("--include-"):
            continue
        # Format: --include-<kind> <symbol>   # header: <path>
        declaration, _, header = line.partition("# header:")
        option, symbol = declaration.split()
        header = header.strip()
        if not header.lower().startswith(jxl_dir):
            continue  # skip system and C runtime headers
        by_header.setdefault(ntpath.basename(header), []).append(f"{option} {symbol}")

    out = ["# jextract symbol filter for libjxl, created by jextract_jxl.py --update-symbols.",
           "# Contains every declaration from the libjxl headers, grouped by header file."]
    for header in sorted(by_header):
        out.append("")
        out.append(f"# {header}")
        out.extend(sorted(by_header[header]))
    SYMBOLS_FILE.write_text("\n".join(out) + "\n", encoding="utf-8", newline="\n")
    count = sum(len(v) for v in by_header.values())
    print(f"Wrote {count} symbols to {SYMBOLS_FILE}")


def write_package_info(package_dir: Path, jxl_version: str, tool_version: str) -> None:
    text = f"""/**
 * Low-level bindings for the libjxl {jxl_version} C API.
 * <p>
 * Created with jextract {tool_version} from {UMBRELLA_HEADER.name} and
 * {SYMBOLS_FILE.name}. Do not edit; regenerate with scripts/{Path(__file__).name}.
 */
package {TARGET_PACKAGE};
"""
    (package_dir / "package-info.java").write_text(text, encoding="utf-8", newline="\n")


def patch_symbol_lookup(package_dir: Path) -> None:
    header = package_dir / f"{HEADER_CLASS}.java"
    text = header.read_text(encoding="utf-8")
    text, count = SYMBOL_LOOKUP_PATTERN.subn(SYMBOL_LOOKUP_REPLACEMENT, text)
    if count != 1:
        raise SystemExit(f"Expected exactly one SYMBOL_LOOKUP initializer in {header}, found {count}")
    text, count = FIND_OR_THROW_PATTERN.subn(FIND_OR_THROW_REPLACEMENT, text)
    if count == 0 or "findOrThrow" in text:
        raise SystemExit(f"Could not replace every findOrThrow in {header} ({count} replaced)")
    header.write_text(text, encoding="utf-8", newline="\n")


def make_platform_neutral(package_dir: Path) -> None:
    """Keep the bindings usable on all 64-bit platforms, or fail if they cannot be."""
    shared = package_dir / f"{HEADER_CLASS}$shared.java"
    text = shared.read_text(encoding="utf-8")
    for constant, c_type in PLATFORM_DEPENDENT_LAYOUTS.items():
        # Widen the declared type and the cast; keep the layout lookup jextract generated.
        pattern = re.compile(rf"public static final ValueLayout\.(\w+) {constant} = \(ValueLayout\.\1\)")
        replacement = f"public static final ValueLayout {constant} = (ValueLayout)"
        text, count = pattern.subn(replacement, text)
        if count != 1:
            raise SystemExit(f"Expected one declaration of {constant} in {shared.name}, found {count}")
        for source in package_dir.glob("*.java"):
            uses = len(re.findall(rf"\b{constant}\b", source.read_text(encoding="utf-8")))
            if uses > (1 if source == shared else 0):
                raise SystemExit(f"{source.name} uses {constant} (C {c_type}); its size differs between "
                                 "platforms, so the bindings would have to be generated per platform")
    shared.write_text(text, encoding="utf-8", newline="\n")


def write_version_class(package_dir: Path, version: tuple[int, int, int]) -> None:
    major, minor, patch = version
    text = f"""package {TARGET_PACKAGE};

/**
 * The libjxl version whose headers these bindings were created from. Struct
 * layouts may differ between minor versions, so the loaded library must match
 * {{@link #MAJOR}} and {{@link #MINOR}}.
 */
public final class LibjxlVersion {{

    /** Major version of libjxl. */
    public static final int MAJOR = {major};

    /** Minor version of libjxl. */
    public static final int MINOR = {minor};

    /** Patch version of libjxl. */
    public static final int PATCH = {patch};

    private LibjxlVersion() {{
    }}
}}
"""
    (package_dir / "LibjxlVersion.java").write_text(text, encoding="utf-8", newline="\n")


def normalize_line_endings(package_dir: Path) -> None:
    """Use LF everywhere; jextract mixes its own line endings with those of the headers."""
    for source in package_dir.glob("*.java"):
        data = source.read_bytes()
        normalized = data.replace(b"\r\n", b"\n")
        if normalized != data:
            source.write_bytes(normalized)


def generate(exe: Path, include_dir: Path) -> None:
    package_path = Path(*TARGET_PACKAGE.split("."))
    package_dir = OUTPUT_DIR / package_path
    # Generate into a temporary directory, so a failing jextract run (libclang
    # occasionally crashes) leaves the checked-in bindings untouched.
    with tempfile.TemporaryDirectory(dir=OUTPUT_DIR.parent) as tmp:
        cmd = [str(exe),
               "--output", tmp,
               "--target-package", TARGET_PACKAGE,
               "--header-class-name", HEADER_CLASS,
               "--include-dir", str(include_dir),
               f"@{SYMBOLS_FILE}",
               str(UMBRELLA_HEADER)]
        output = run(cmd)
        if output.strip():
            print(output.strip())

        generated = Path(tmp) / package_path
        if not (generated / f"{HEADER_CLASS}.java").is_file():
            raise SystemExit(f"jextract produced no {HEADER_CLASS}.java; the bindings were not changed")
        normalize_line_endings(generated)
        patch_symbol_lookup(generated)
        make_platform_neutral(generated)
        jxl_version = libjxl_version(include_dir)
        tool_version = jextract_version(exe)
        write_package_info(generated, jxl_version, tool_version)
        write_version_class(generated, libjxl_version_parts(include_dir))

        if package_dir.exists():
            shutil.rmtree(package_dir)
        shutil.copytree(generated, package_dir)
    count = sum(1 for _ in package_dir.glob("*.java"))
    print(f"Generated {count} files in {package_dir} (libjxl {jxl_version}, jextract {tool_version})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tools", type=Path, default=None,
                        help=f"tools directory of fetch_tools.py (default: ${ENVIRONMENT_VARIABLE} "
                             f"or {DEFAULT_TOOLS_DIR})")
    parser.add_argument("--jextract-home", type=Path, default=None,
                        help=f"jextract installation (default: <tools>/{JEXTRACT_DIR})")
    parser.add_argument("--jxl-home", type=Path, default=None,
                        help=f"libjxl distribution with headers (default: <tools>/{LIBJXL_DIR})")
    parser.add_argument("--update-symbols", action="store_true",
                        help="rebuild jxl-symbols.txt from the libjxl headers first")
    args = parser.parse_args()

    tools = tools_dir(args.tools)
    exe = jextract_executable(args.jextract_home or tools / JEXTRACT_DIR)
    include_dir = ((args.jxl_home or tools / LIBJXL_DIR) / "include").resolve()
    if args.update_symbols or not SYMBOLS_FILE.exists():
        update_symbols(exe, include_dir)
    generate(exe, include_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
