"""Build the files for a panamage release on GitHub.

Builds the project with all tests (optionally also on Linux in WSL) and writes
to dist/<version>/:
  * the five JARs
  * panamage-<version>-windows-x86_64.zip and panamage-<version>-linux-x86_64.tar.gz,
    each with the JARs for the platform, README.txt, Example.java, LICENSE and
    the licenses of the bundled native libraries
  * SHA256SUMS for all files, in the format of sha256sum

The version is taken from the root POM and must not be a snapshot.

Usage:
  python make_release.py [--skip-build] [--wsl]
"""

import argparse
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tarfile
import time
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from string import Template

from tools_dir import PROJECT_DIR

SCRIPTS_DIR = Path(__file__).resolve().parent
TEMPLATES_DIR = SCRIPTS_DIR / "release"
DIST_DIR = PROJECT_DIR / "dist"

COMMON_MODULES = ["panamage-jxl-spi", "panamage-jxl", "panamage-jxl-imageio"]

PLATFORMS = {
    "windows-x86_64": {
        "archive": "zip",
        "requirements": "Windows 10 or newer on x86_64 (64-bit Intel or AMD)",
    },
    "linux-x86_64": {
        "archive": "tar.gz",
        "requirements": "Linux on x86_64 with glibc 2.29 or newer (for example Ubuntu 20.04,\n"
                        "  Debian 11, RHEL 9 or newer); musl-based systems such as Alpine are\n"
                        "  not supported",
    },
}

POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


def pom_values() -> tuple[str, str]:
    """Returns the project version and the bundled libjxl version from the root POM."""
    root = ET.parse(PROJECT_DIR / "pom.xml").getroot()
    version = root.findtext("m:version", namespaces=POM_NAMESPACE)
    libjxl = root.findtext("m:properties/m:libjxl.version", namespaces=POM_NAMESPACE)
    if not version or not libjxl:
        raise SystemExit("version or libjxl.version not found in pom.xml")
    return version.strip(), libjxl.strip()


def run(cmd: list[str]) -> None:
    print(f"> {' '.join(cmd)}", flush=True)
    subprocess.run(cmd, cwd=PROJECT_DIR, check=True)


def build(wsl: bool) -> None:
    if wsl:
        # Runs the tests on Linux; the JARs are rebuilt on this machine afterwards.
        run([sys.executable, str(SCRIPTS_DIR / "wsl_verify.py")])
    wrapper = PROJECT_DIR / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    run([str(wrapper), "-B", "clean", "verify"])


def module_jar(module: str, version: str) -> Path:
    jar = PROJECT_DIR / module / "target" / f"{module}-{version}.jar"
    if not jar.is_file():
        raise SystemExit(f"{jar} not found; build the project first or omit --skip-build")
    return jar


def bundled_licenses(natives_jar: Path) -> dict[str, bytes]:
    """The license files of the native libraries, from META-INF/licenses of the JAR."""
    prefix = "META-INF/licenses/"
    with zipfile.ZipFile(natives_jar) as jar:
        return {name.removeprefix(prefix): jar.read(name)
                for name in jar.namelist() if name.startswith(prefix) and not name.endswith("/")}


def archive_entries(platform: str, version: str, libjxl_version: str) -> dict[str, bytes]:
    """Relative path in the archive -> content."""
    natives_module = f"panamage-jxl-natives-{platform}"
    natives_jar = module_jar(natives_module, version)
    readme = Template((TEMPLATES_DIR / "README.txt").read_text(encoding="utf-8")).substitute(
        version=version,
        platform=platform,
        libjxl_version=libjxl_version,
        requirements=PLATFORMS[platform]["requirements"],
        natives_jar=natives_jar.name,
    )
    newline = "\r\n" if platform.startswith("windows") else "\n"
    entries = {f"lib/{module_jar(m, version).name}": module_jar(m, version).read_bytes() for m in COMMON_MODULES}
    entries[f"lib/{natives_jar.name}"] = natives_jar.read_bytes()
    entries["README.txt"] = readme.replace("\n", newline).encode("utf-8")
    entries["Example.java"] = (TEMPLATES_DIR / "Example.java").read_bytes()
    entries["LICENSE"] = (PROJECT_DIR / "LICENSE").read_bytes()
    for name, content in bundled_licenses(natives_jar).items():
        entries[f"licenses/{name}"] = content
    return entries


def write_zip(path: Path, root: str, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in entries.items():
            archive.writestr(f"{root}/{name}", content)


def write_tar_gz(path: Path, root: str, entries: dict[str, bytes]) -> None:
    now = int(time.time())
    with tarfile.open(path, "w:gz") as archive:
        for name, content in entries.items():
            info = tarfile.TarInfo(f"{root}/{name}")
            info.size = len(content)
            info.mode = 0o644
            info.mtime = now
            archive.addfile(info, io.BytesIO(content))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--skip-build", action="store_true", help="use the JARs that are already built")
    parser.add_argument("--wsl", action="store_true", help="also run all tests on Linux in WSL first")
    args = parser.parse_args()

    version, libjxl_version = pom_values()
    if version.endswith("-SNAPSHOT"):
        raise SystemExit(f"Version {version} is a snapshot; set a release version in pom.xml first")
    if not args.skip_build:
        build(args.wsl)

    dist = DIST_DIR / version
    if dist.exists():
        try:
            shutil.rmtree(dist)
        except PermissionError as e:
            raise SystemExit(f"Cannot replace {dist}: {e.filename} is in use by another program "
                             "(for example an archive viewer). Close it and run again with --skip-build.")
    dist.mkdir(parents=True)

    for module in COMMON_MODULES + [f"panamage-jxl-natives-{p}" for p in PLATFORMS]:
        shutil.copy2(module_jar(module, version), dist)
    for platform, settings in PLATFORMS.items():
        root = f"panamage-{version}-{platform}"
        entries = archive_entries(platform, version, libjxl_version)
        if settings["archive"] == "zip":
            write_zip(dist / f"{root}.zip", root, entries)
        else:
            write_tar_gz(dist / f"{root}.tar.gz", root, entries)

    files = sorted(p for p in dist.iterdir() if p.is_file())
    sums = "".join(f"{sha256(p)}  {p.name}\n" for p in files)
    (dist / "SHA256SUMS").write_text(sums, encoding="ascii", newline="\n")

    print(f"\nRelease files in {dist}:")
    for path in files + [dist / "SHA256SUMS"]:
        print(f"  {path.stat().st_size:>10,}  {path.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
