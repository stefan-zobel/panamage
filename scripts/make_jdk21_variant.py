"""Generate the sources of panamage-jxl-jdk21 from the JDK 25 sources.

JDK 21 has the Foreign Function and Memory API as a preview (JEP 442), with
a few differences to the final API of JDK 22: allocateArray and
allocateUtf8String instead of allocateFrom, and jextract 21 generates other
accessor names (xsize$get, xsize$set, animation$slice, $LAYOUT()) and no
address getters for functions. This script derives the JDK 21 variant
mechanically, so the JDK 25 sources stay the only ones maintained by hand.

  --update-bindings  downloads jextract 21 into the tools directory
                     (jextract-21), verified against its published SHA-256,
                     unless it is there already (published for Windows,
                     Linux and macOS on x64 only), and generates the libjxl
                     bindings from the headers of the libjxl release (see
                     fetch_tools.py) into panamage-jxl-jdk21/bindings, where
                     they are checked in. Run it again when jxl_api.h,
                     jxl-symbols.txt or the libjxl version changes.
  --generate DIR     writes the sources of panamage-jxl-jdk21 to DIR
                     (main/java, main/resources, test/java, test/resources):
                     those of panamage-jxl and panamage-jxl-imageio without
                     the module descriptors and the test of the named module,
                     with the checked-in bindings instead of the JDK 25 ones,
                     rewritten for the JDK 21 preview API. The Maven build of
                     panamage-jxl-jdk21 (profile jdk21) runs this.

The bindings find the libraries through panamage.jxl.internal.NativeLibraries,
as the JDK 25 bindings do, so panamage-jxl-jdk21 uses the same native bundles.
Classes compiled with --enable-preview run only on exactly JDK 21, with
--enable-preview at run time.

Usage:
  python make_jdk21_variant.py --update-bindings [--tools DIR]
  python make_jdk21_variant.py --generate DIR
"""

import argparse
import hashlib
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

from tools_dir import DEFAULT_TOOLS_DIR, ENVIRONMENT_VARIABLE, PROJECT_DIR, tools_dir

JEXTRACT_BASE_URL = "https://download.java.net/java/early_access/jextract/21/1/openjdk-21-jextract+1-2_"
# jextract 21 was published for x64 only.
JEXTRACT_PLATFORMS = {
    ("win32", "amd64"): "windows-x64",
    ("linux", "x86_64"): "linux-x64",
    ("darwin", "x86_64"): "macos-x64",
}
JEXTRACT_DIR = "jextract-21"
LIBJXL_DIR = "libjxl-0.12.0"

SCRIPTS_DIR = Path(__file__).resolve().parent
UMBRELLA_HEADER = SCRIPTS_DIR / "jxl_api.h"
SYMBOLS_FILE = SCRIPTS_DIR / "jxl-symbols.txt"

FFI_PACKAGE = Path("panamage", "jxl", "ffi")
MAIN_FFI_DIR = PROJECT_DIR / "panamage-jxl" / "src" / "main" / "java" / FFI_PACKAGE
BINDINGS_DIR = PROJECT_DIR / "panamage-jxl-jdk21" / "bindings"
SOURCE_MODULES = ["panamage-jxl", "panamage-jxl-imageio"]

# Tests that only make sense in the named module.
MODULE_ONLY_TESTS = ["panamage/jxl/ModuleSetupTest.java"]

LOOKUP_PATTERN = re.compile(r"SymbolLookup loaderLookup = SymbolLookup\.loaderLookup\(\);\s*"
                            r"SYMBOL_LOOKUP = [^;]+;")
LOOKUP_REPLACEMENT = "SYMBOL_LOOKUP = panamage.jxl.internal.NativeLibraries.lookup();"
# jextract 21 has no address getters for functions; the address of a native
# function (such as the parallel runner) is looked up by name instead.
FUNCTION_ADDRESS = re.compile(r"Jxl\.(\w+)\$address\(\)")
FUNCTION_LOOKUP = r'panamage.jxl.internal.NativeLibraries.lookup().find("\1").orElseThrow()'

# C types whose size differs between 64-bit platforms (long is 32 bits on Windows);
# jextract 21 declares them with the size of the platform it runs on.
PLATFORM_DEPENDENT_LAYOUTS = {"C_LONG": "long", "C_LONG_DOUBLE": "long double"}


def fetch_jextract(tools: Path) -> Path:
    dest = tools / JEXTRACT_DIR
    if dest.exists():
        return dest
    jextract_platform = JEXTRACT_PLATFORMS.get((sys.platform, platform.machine().lower()))
    if jextract_platform is None:
        raise SystemExit(f"No jextract 21 build for {sys.platform} {platform.machine()}")
    url = f"{JEXTRACT_BASE_URL}{jextract_platform}_bin.tar.gz"
    downloads = tools / "_downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    archive = downloads / url.rsplit("/", 1)[1]
    if not archive.exists():
        print(f"Downloading {url}", flush=True)
        partial = archive.with_name(archive.name + ".part")
        with urllib.request.urlopen(url) as response, open(partial, "wb") as out:
            shutil.copyfileobj(response, out)
        partial.replace(archive)
    with urllib.request.urlopen(url + ".sha256") as response:
        expected = response.read().decode("ascii").split()[0]
    actual = hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual != expected:
        archive.unlink()
        raise SystemExit(f"SHA-256 mismatch for {archive.name}: expected {expected}, got {actual}")
    with tempfile.TemporaryDirectory(dir=tools) as tmp:
        with tarfile.open(archive) as tar:
            tar.extractall(tmp, filter="data")
        entries = list(Path(tmp).iterdir())
        root = entries[0] if len(entries) == 1 and entries[0].is_dir() else Path(tmp)
        shutil.copytree(root, dest)
    print(f"jextract 21 unpacked to {dest}", flush=True)
    return dest


def make_platform_neutral(package_dir: Path) -> None:
    """Removes the unused platform-dependent layouts, or fails if they are used."""
    header = package_dir / "Jxl.java"
    text = header.read_text(encoding="utf-8")
    for constant in PLATFORM_DEPENDENT_LAYOUTS:
        text = re.sub(rf"^ *public static final \w+ {constant} = \w+;\n", "", text, flags=re.MULTILINE)
    header.write_text(text, encoding="utf-8", newline="\n")
    for source in package_dir.glob("*.java"):
        for constant, c_type in PLATFORM_DEPENDENT_LAYOUTS.items():
            if re.search(rf"\b{constant}\b", source.read_text(encoding="utf-8")):
                raise SystemExit(f"{source.name} uses {constant} (C {c_type}); its size differs between "
                                 "platforms, so the bindings would have to be generated per platform")


def write_package_info(package_dir: Path, libjxl_version: str) -> None:
    text = f"""/**
 * Low-level bindings for the libjxl {libjxl_version} C API, for the preview of the
 * Foreign Function and Memory API in JDK 21.
 * <p>
 * Created with jextract 21 from jxl_api.h and jxl-symbols.txt. Do not edit;
 * regenerate with scripts/make_jdk21_variant.py --update-bindings.
 */
package panamage.jxl.ffi;
"""
    (package_dir / "package-info.java").write_text(text, encoding="utf-8", newline="\n")


def update_bindings(jextract: Path, include_dir: Path) -> None:
    """Generates the bindings into panamage-jxl-jdk21/bindings/panamage/jxl/ffi."""
    if not include_dir.is_dir():
        raise SystemExit(f"{include_dir} not found; run fetch_tools.py without --build-only")
    exe = jextract / "bin" / ("jextract.bat" if os.name == "nt" else "jextract")
    with tempfile.TemporaryDirectory() as tmp:
        result = subprocess.run([str(exe), "--source", "--output", tmp,
                                 "--target-package", "panamage.jxl.ffi", "--header-class-name", "Jxl",
                                 "-I", str(include_dir), f"@{SYMBOLS_FILE}", str(UMBRELLA_HEADER)],
                                capture_output=True, text=True)
        if result.returncode != 0:
            sys.stderr.write(result.stdout + result.stderr)
            raise SystemExit("jextract 21 failed")
        generated = Path(tmp) / FFI_PACKAGE
        for source in generated.glob("*.java"):
            source.write_bytes(source.read_bytes().replace(b"\r\n", b"\n"))
        helper = generated / "RuntimeHelper.java"
        text, count = LOOKUP_PATTERN.subn(LOOKUP_REPLACEMENT, helper.read_text(encoding="utf-8"))
        if count != 1:
            raise SystemExit("Expected one symbol lookup initializer in RuntimeHelper.java")
        helper.write_text(text, encoding="utf-8", newline="\n")
        make_platform_neutral(generated)
        write_package_info(generated, LIBJXL_DIR.removeprefix("libjxl-"))
        destination = BINDINGS_DIR / FFI_PACKAGE
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(generated, destination)
    print(f"Generated {sum(1 for _ in destination.glob('*.java'))} files in {destination}", flush=True)


def struct_members(ffi_dir: Path) -> dict[str, tuple[set[str], set[str]]]:
    """Struct class -> (fields with getters and setters, nested structs with slices)."""
    structs = {}
    for source in ffi_dir.glob("*.java"):
        text = source.read_text(encoding="utf-8")
        if "$LAYOUT()" not in text:
            continue
        fields = set(re.findall(r"public static \S+ (\w+)\$get\(MemorySegment seg\)", text))
        slices = set(re.findall(r"public static MemorySegment (\w+)\$slice\(MemorySegment seg\)", text))
        structs[source.stem] = (fields, slices)
    return structs


def count_arguments(text: str, open_paren: int) -> int:
    """The number of top-level arguments of the call whose '(' is at open_paren."""
    depth = 0
    commas = 0
    empty = True
    for i in range(open_paren, len(text)):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return 0 if empty else commas + 1
        elif c == "," and depth == 1:
            commas += 1
        elif depth >= 1 and not c.isspace():
            empty = False
    raise ValueError("unbalanced parentheses")


def port_struct_accessors(text: str, structs: dict[str, tuple[set[str], set[str]]]) -> str:
    if not structs:
        return text
    names = "|".join(sorted(structs, key=len, reverse=True))
    out = []
    position = 0
    for match in re.finditer(rf"\b({names})\.(\w+)\(", text):
        struct, member = match.group(1), match.group(2)
        fields, slices = structs[struct]
        arguments = count_arguments(text, match.end() - 1)
        if member == "layout" and arguments == 0:
            replacement = f"{struct}.$LAYOUT("
        elif member in slices and arguments == 1:
            replacement = f"{struct}.{member}$slice("
        elif member in fields and arguments == 1:
            replacement = f"{struct}.{member}$get("
        elif member in fields and arguments == 2:
            replacement = f"{struct}.{member}$set("
        else:
            continue
        out.append(text[position:match.start()])
        out.append(replacement)
        position = match.end()
    out.append(text[position:])
    return "".join(out)


def port_source(text: str, structs: dict[str, tuple[set[str], set[str]]]) -> str:
    """Rewrites one hand-written source file from the JDK 22 API to the JDK 21 preview API."""
    text = port_struct_accessors(text, structs)
    text = re.sub(r"\.allocateFrom\(((?:ValueLayout\.)?JAVA_\w+),", r".allocateArray(\1,", text)
    text = text.replace(".allocateFrom(", ".allocateUtf8String(")
    return FUNCTION_ADDRESS.sub(FUNCTION_LOOKUP, text)


def port_tree(root: Path, structs: dict[str, tuple[set[str], set[str]]]) -> None:
    for source in root.rglob("*.java"):
        if FFI_PACKAGE.as_posix() in source.relative_to(root).as_posix():
            continue
        text = source.read_text(encoding="utf-8")
        ported = port_source(text, structs)
        if ported != text:
            source.write_text(ported, encoding="utf-8", newline="\n")


def copy_sources(source: Path, target: Path) -> None:
    if source.exists():
        shutil.copytree(source, target, dirs_exist_ok=True, ignore=shutil.ignore_patterns("module-info.java"))


def generate(output: Path) -> None:
    """Writes the ported sources and resources of panamage-jxl-jdk21 to output."""
    bindings = BINDINGS_DIR / FFI_PACKAGE
    if not bindings.is_dir():
        raise SystemExit(f"{bindings} not found; run make_jdk21_variant.py --update-bindings")
    if output.exists():
        shutil.rmtree(output)
    main_java = output / "main" / "java"
    main_resources = output / "main" / "resources"
    test_java = output / "test" / "java"
    test_resources = output / "test" / "resources"
    for module in SOURCE_MODULES:
        src = PROJECT_DIR / module / "src"
        copy_sources(src / "main" / "java", main_java)
        copy_sources(src / "test" / "java", test_java)
        for source, target in ((src / "main" / "resources", main_resources),
                               (src / "test" / "resources", test_resources)):
            if source.exists():
                shutil.copytree(source, target, dirs_exist_ok=True)
    for test in MODULE_ONLY_TESTS:
        (test_java / test).unlink(missing_ok=True)
    shutil.rmtree(main_java / FFI_PACKAGE)
    shutil.copytree(bindings, main_java / FFI_PACKAGE)
    # The version class is not generated by jextract; it describes the same headers.
    shutil.copyfile(MAIN_FFI_DIR / "LibjxlVersion.java", main_java / FFI_PACKAGE / "LibjxlVersion.java")
    main_resources.mkdir(parents=True, exist_ok=True)
    test_resources.mkdir(parents=True, exist_ok=True)

    structs = struct_members(main_java / FFI_PACKAGE)
    port_tree(main_java, structs)
    port_tree(test_java, structs)
    print(f"Generated the JDK 21 sources in {output}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--update-bindings", action="store_true",
                      help="generate the checked-in bindings with jextract 21")
    mode.add_argument("--generate", type=Path, metavar="DIR",
                      help="write the sources of panamage-jxl-jdk21 to DIR")
    parser.add_argument("--tools", type=Path, default=None,
                        help=f"tools directory (default: ${ENVIRONMENT_VARIABLE} or {DEFAULT_TOOLS_DIR})")
    args = parser.parse_args()
    if args.update_bindings:
        tools = tools_dir(args.tools)
        update_bindings(fetch_jextract(tools), (tools / LIBJXL_DIR / "include").resolve())
    else:
        generate(args.generate.resolve())
    return 0


if __name__ == "__main__":
    sys.exit(main())
