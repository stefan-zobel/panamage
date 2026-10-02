"""Build the files for a panamage release on GitHub and Maven Central.

Builds the project with all tests (optionally also on Linux in WSL) and writes
to dist/<version>/:
  * the JARs of all modules, including panamage-jxl-jdk21 (built with the
    profile jdk21, so the Maven toolchains file needs a JDK 21 as well as a
    JDK 25; its sources are generated with the Python that runs this script;
    a JDK 8 in the toolchains file is used by the tests that check that the
    Image I/O plugin stays out of the way on Java 8, which are skipped
    without it)
  * panamage-jxl-imagej-<version>.jar, the ImageJ and Fiji plugin (also built
    with the profile jdk21); it is published on the Fiji update site (see
    make_update_site.py), not on Maven Central
  * panamage-<version>-<platform>.zip for windows-x86_64 and
    panamage-<version>-<platform>.tar.gz for linux-x86_64, linux-aarch64,
    linux-musl-x86_64, linux-musl-aarch64 and macos-aarch64, each with the
    JARs for the platform, README.txt, Example.java, LICENSE and the licenses
    of the bundled native libraries
  * SHA256SUMS for all files, in the format of sha256sum

With --central, it also writes central/panamage-<version>-central.zip, the
bundle for a manual upload to the Maven Central Portal: the parent POM and,
for every module except the ImageJ plugin, its POM, JAR, sources JAR and Javadoc JAR in the Maven
repository layout, each with a GPG signature (.asc) and MD5 and SHA-1
checksums. The files are signed with gpg (--gpg, or the PANAMAGE_GPG
environment variable, or gpg on the PATH) and the key given by --gpg-key or
the PANAMAGE_GPG_KEY environment variable (a fingerprint or key ID), or else
the default key of gpg; gpg may ask for the passphrase. Nothing is uploaded.

The version is taken from the root POM and must not be a snapshot, except with
--dry-run, which writes to target/release-dry-run/<version>/ instead of dist/
to try out the release steps.

Usage:
  python make_release.py [--skip-build] [--wsl] [--central [--gpg PATH] [--gpg-key ID]] [--dry-run]
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

DRY_RUN_DIR = PROJECT_DIR / "target" / "release-dry-run"

COMMON_MODULES = ["panamage-jxl-spi", "panamage-jxl", "panamage-jxl-imageio"]
# Published with the other modules, but not part of the platform archives.
JDK21_MODULE = "panamage-jxl-jdk21"
# Only in dist/ and on the Fiji update site: it depends on ImageJ artifacts that are
# published only in the SciJava repository, not on Maven Central.
IMAGEJ_MODULE = "panamage-jxl-imagej"
TOOLCHAINS_FILE = Path.home() / ".m2" / "toolchains.xml"

GROUP_ID = "net.sourceforge.streamsupport"
PARENT_ARTIFACT = "panamage"
GPG_ENVIRONMENT_VARIABLE = "PANAMAGE_GPG"
GPG_KEY_ENVIRONMENT_VARIABLE = "PANAMAGE_GPG_KEY"
# POM elements Maven Central requires; all but name may be inherited from the parent POM.
REQUIRED_POM_ELEMENTS = ["name", "description", "url", "licenses", "developers", "scm"]
# Classifiers of the JARs of every module: the classes, the sources and the Javadoc.
JAR_CLASSIFIERS = ["", "-sources", "-javadoc"]

PLATFORMS = {
    "windows-x86_64": {
        "archive": "zip",
        "requirements": "Windows 10 or newer on x86_64 (64-bit Intel or AMD); nothing else needs\n"
                        "  to be installed",
    },
    "linux-x86_64": {
        "archive": "tar.gz",
        "requirements": "Linux on x86_64 with glibc 2.29 or newer (for example Ubuntu 20.04,\n"
                        "  Debian 11, RHEL 9 or newer); for musl-based systems such as\n"
                        "  Alpine, use the linux-musl-x86_64 archive",
    },
    "linux-aarch64": {
        "archive": "tar.gz",
        "requirements": "Linux on aarch64 (64-bit ARM) with glibc 2.28 or newer (for example\n"
                        "  Ubuntu 20.04, Debian 10, RHEL 8, Amazon Linux 2023 or newer);\n"
                        "  for musl-based systems such as Alpine, use the linux-musl-aarch64\n"
                        "  archive",
    },
    "linux-musl-x86_64": {
        "archive": "tar.gz",
        "requirements": "Linux on x86_64 with the musl C library 1.2.4 or newer (for example\n"
                        "  Alpine Linux 3.18 or newer); nothing else needs to be installed",
    },
    "linux-musl-aarch64": {
        "archive": "tar.gz",
        "requirements": "Linux on aarch64 (64-bit ARM) with the musl C library 1.2.4 or newer\n"
                        "  (for example Alpine Linux 3.18 or newer); nothing else needs to be\n"
                        "  installed",
    },
    "macos-aarch64": {
        "archive": "tar.gz",
        "requirements": "macOS 11 or newer on Apple silicon (arm64)",
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


def toolchain_jdk_homes() -> dict[str, str]:
    """Returns the home directories of the JDKs in the toolchains file by their version."""
    homes = {}
    if TOOLCHAINS_FILE.is_file():
        for toolchain in ET.parse(TOOLCHAINS_FILE).getroot().iter():
            if toolchain.tag.rsplit("}", 1)[-1] != "toolchain":
                continue
            fields = {e.tag.rsplit("}", 1)[-1]: (e.text or "").strip() for e in toolchain.iter()}
            if fields.get("type") == "jdk" and fields.get("version"):
                homes.setdefault(fields["version"], fields.get("jdkHome", ""))
    return homes


def jdk_test_properties() -> list[str]:
    """Returns the Maven properties with the JDK 8 and JDK 21 for the tests of the Image I/O plugin.

    Fails before the long build if the toolchains file has no JDK 21, which
    panamage-jxl-jdk21 is built with. Without a JDK 8, the tests that check
    that the plugin stays out of the way on Java 8 are skipped.
    """
    homes = toolchain_jdk_homes()
    jdk21 = homes.get("21")
    if not jdk21:
        raise SystemExit(f"{TOOLCHAINS_FILE} has no JDK 21, which {JDK21_MODULE} is built with; add one, "
                         "for example with write_toolchains.py --jdk JDK25_HOME --jdk21 JDK21_HOME")
    jdk8 = homes.get("8") or homes.get("1.8")
    if not jdk8:
        print(f"Note: {TOOLCHAINS_FILE} has no JDK 8 (version 8 or 1.8), so the tests of the Image I/O "
              "plugin on Java 8 are skipped.", flush=True)
    return [f"-Djdk8.home={jdk8 or ''}", f"-Djdk21.home={jdk21}"]


def build(wsl: bool, jdk_properties: list[str]) -> None:
    if wsl:
        # Runs the tests on Linux; the JARs are rebuilt on this machine afterwards.
        run([sys.executable, str(SCRIPTS_DIR / "wsl_verify.py")])
    wrapper = PROJECT_DIR / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    run([str(wrapper), "-B", "-Pjdk21", f"-Dpython.executable={sys.executable}", *jdk_properties,
         "clean", "verify"])


def module_jar(module: str, version: str, classifier: str = "") -> Path:
    jar = PROJECT_DIR / module / "target" / f"{module}-{version}{classifier}.jar"
    if not jar.is_file():
        raise SystemExit(f"{jar} not found; build the project first or omit --skip-build")
    return jar


def jar_modules() -> list[str]:
    return COMMON_MODULES + [f"panamage-jxl-natives-{p}" for p in PLATFORMS] + [JDK21_MODULE]


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


def check_pom(pom: Path, parent: ET.Element, version: str) -> None:
    """Fails unless the POM has the information Maven Central requires, itself or from the parent."""
    root = ET.parse(pom).getroot()
    for element in REQUIRED_POM_ELEMENTS:
        own = root.find(f"m:{element}", POM_NAMESPACE)
        inherited = parent.find(f"m:{element}", POM_NAMESPACE) if element != "name" else None
        if own is None and inherited is None:
            raise SystemExit(f"{pom}: <{element}> is missing")
    parent_ref = root.find("m:parent", POM_NAMESPACE)
    pom_version = (parent_ref if parent_ref is not None else root).findtext("m:version", namespaces=POM_NAMESPACE)
    if (pom_version or "").strip() != version:
        raise SystemExit(f"{pom}: version {pom_version} instead of {version}")
    if parent_ref is None and root.find("m:properties/m:project.build.outputTimestamp", POM_NAMESPACE) is None:
        raise SystemExit(f"{pom}: project.build.outputTimestamp is not set; the JARs are not reproducible")


def gpg_command(gpg: str, key: str | None) -> list[str]:
    """Checks that gpg runs and has the secret key to sign with (or any secret key without one)."""
    try:
        listing = subprocess.run([gpg, "--batch", "--list-secret-keys", "--with-colons", *([key] if key else [])],
                                 capture_output=True, text=True)
    except OSError as e:
        raise SystemExit(f"Cannot run {gpg}: {e}; pass the path of gpg with --gpg "
                         f"or the {GPG_ENVIRONMENT_VARIABLE} environment variable")
    if listing.returncode != 0 or not any(line.startswith("sec:") for line in listing.stdout.splitlines()):
        if key:
            raise SystemExit(f"{gpg} has no secret key {key}; check --gpg-key or the "
                             f"{GPG_KEY_ENVIRONMENT_VARIABLE} environment variable, and --gpg")
        raise SystemExit(f"{gpg} has no secret key; pass the gpg that holds your key with --gpg")
    return [gpg]


def sign(gpg: list[str], key: str | None, path: Path) -> None:
    """Writes the ASCII-armored detached signature <path>.asc and verifies it."""
    signature = path.with_name(path.name + ".asc")
    # Without --batch, so that gpg can ask for the passphrase.
    run([*gpg, "--yes", "--armor", "--detach-sign", *(["--local-user", key] if key else []),
         "--output", str(signature), str(path)])
    subprocess.run([*gpg, "--batch", "--verify", str(signature), str(path)], check=True, capture_output=True)


def write_central_bundle(dist: Path, version: str, gpg: list[str], key: str | None) -> Path:
    """Writes the bundle for the Maven Central Portal and returns its path."""
    parent_pom = PROJECT_DIR / "pom.xml"
    parent = ET.parse(parent_pom).getroot()
    # Repository path -> source file.
    files: dict[str, Path] = {}
    for artifact, pom in [(PARENT_ARTIFACT, parent_pom)] + [(m, PROJECT_DIR / m / "pom.xml") for m in jar_modules()]:
        check_pom(pom, parent, version)
        directory = f"{GROUP_ID.replace('.', '/')}/{artifact}/{version}"
        files[f"{directory}/{artifact}-{version}.pom"] = pom
        if artifact != PARENT_ARTIFACT:
            for classifier in JAR_CLASSIFIERS:
                jar = module_jar(artifact, version, classifier)
                if classifier == "" and jar.read_bytes() != (dist / jar.name).read_bytes():
                    raise SystemExit(f"{jar.name} differs from the JAR in {dist}")
                files[f"{directory}/{artifact}-{version}{classifier}.jar"] = jar

    central = dist / "central"
    if central.exists():
        shutil.rmtree(central)
    staging = central / "staging"
    for path, source in files.items():
        target = staging / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        sign(gpg, key, target)
        data = target.read_bytes()
        target.with_name(target.name + ".md5").write_text(hashlib.md5(data).hexdigest(), encoding="ascii")
        target.with_name(target.name + ".sha1").write_text(hashlib.sha1(data).hexdigest(), encoding="ascii")

    bundle = central / f"panamage-{version}-central.zip"
    with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(p for p in staging.rglob("*") if p.is_file()):
            archive.write(path, path.relative_to(staging).as_posix())
    shutil.rmtree(staging)
    print(f"\nMaven Central bundle {bundle} ({bundle.stat().st_size:,} bytes): "
          f"{len(files)} files, each with .asc, .md5 and .sha1")
    return bundle


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--skip-build", action="store_true", help="use the JARs that are already built")
    parser.add_argument("--wsl", action="store_true", help="also run all tests on Linux in WSL first")
    parser.add_argument("--central", action="store_true",
                        help="also write the signed bundle for the Maven Central Portal")
    parser.add_argument("--gpg", default=os.environ.get(GPG_ENVIRONMENT_VARIABLE, "gpg"),
                        help=f"gpg executable for signing (default: ${GPG_ENVIRONMENT_VARIABLE} or gpg)")
    parser.add_argument("--gpg-key", default=os.environ.get(GPG_KEY_ENVIRONMENT_VARIABLE, "").strip() or None,
                        help=f"fingerprint or key ID of the key to sign with "
                             f"(default: ${GPG_KEY_ENVIRONMENT_VARIABLE} or the default key of gpg)")
    parser.add_argument("--dry-run", action="store_true",
                        help="allow a snapshot version and write to target/release-dry-run instead of dist")
    args = parser.parse_args()

    version, libjxl_version = pom_values()
    if version.endswith("-SNAPSHOT") and not args.dry_run:
        raise SystemExit(f"Version {version} is a snapshot; set a release version in pom.xml first")
    # Fail before the long build if the bundle cannot be signed.
    gpg = gpg_command(args.gpg, args.gpg_key) if args.central else []
    if not args.skip_build:
        build(args.wsl, jdk_test_properties())

    dist = (DRY_RUN_DIR if args.dry_run else DIST_DIR) / version
    if dist.exists():
        try:
            shutil.rmtree(dist)
        except PermissionError as e:
            raise SystemExit(f"Cannot replace {dist}: {e.filename} is in use by another program "
                             "(for example an archive viewer). Close it and run again with --skip-build.")
    dist.mkdir(parents=True)

    for module in jar_modules() + [IMAGEJ_MODULE]:
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

    if args.central:
        write_central_bundle(dist, version, gpg, args.gpg_key)
    return 0


if __name__ == "__main__":
    sys.exit(main())
