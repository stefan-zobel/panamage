"""Upload the ImageJ plugin of panamage to a Fiji update site.

Copies the plugin and what it needs into a Fiji installation, in the layout of
the update site, and uploads these files with the Fiji updater:

  jars/panamage-jxl-imagej-<version>.jar      the plugin
  jars/panamage-jxl-jdk21-<version>.jar       panamage for Java 21
  jars/panamage-jxl-spi-<version>.jar
  jars/win64/panamage-jxl-natives-windows-x86_64-<version>.jar
  jars/linux64/panamage-jxl-natives-linux-x86_64-<version>.jar
  jars/linux-arm64/panamage-jxl-natives-linux-aarch64-<version>.jar
  jars/macos-arm64/panamage-jxl-natives-macos-aarch64-<version>.jar
  config/jaunch/extra-panamage.toml           starts Java 21 with --enable-preview

Fiji installs only the native libraries of its own platform. Older panamage
files in the Fiji installation are removed first.

The JARs come from dist/<version>/ (see make_release.py) or, with
--from-target, from the target directories of the modules after a build with
the profile jdk21, for example to try out a snapshot. The version is the one
in pom.xml unless --version names another, for example a release in dist/
after pom.xml has moved on to the next snapshot.

The upload goes to the personal update site NAME on sites.imagej.net
(https://sites.imagej.net/NAME/) as the given WebDAV user; the updater asks
for the password. With --local-site DIR, it goes to a local test site instead
(a file: URL that any Fiji installation can add). With --simulate, the updater
only shows what it would upload.

The Fiji installation must have Java 21 (Fiji "latest"). The updater runs
directly with Fiji's Java, because the Fiji launcher hides its output on
Windows. Its first run checksums the whole installation and takes a while.

Usage:
  python make_update_site.py --fiji DIR [--version VERSION] [--from-target]
                             [--simulate]
                             (--site NAME --webdav-user USER | --local-site DIR)
"""

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

from make_release import DIST_DIR, pom_values
from tools_dir import PROJECT_DIR

# The platform directories of the Fiji updater for the native bundles.
PLATFORM_DIRS = {
    "windows-x86_64": "win64",
    "linux-x86_64": "linux64",
    "linux-aarch64": "linux-arm64",
    "macos-aarch64": "macos-arm64",
}
PLUGIN_MODULES = ["panamage-jxl-imagej", "panamage-jxl-jdk21", "panamage-jxl-spi"]
LAUNCHER_FILE = "config/jaunch/extra-panamage.toml"
LAUNCHER_SOURCE = PROJECT_DIR / "panamage-jxl-imagej" / "src" / "fiji" / LAUNCHER_FILE
LOCAL_SITE_NAME = "panamage-local"
SITES_URL = "https://sites.imagej.net/{name}/"
JAVA_RELEASE = "21"


def site_files(version: str, from_target: bool) -> dict[str, Path]:
    """Returns the source of every file of the update site, by its path in Fiji."""
    modules = {module: "jars" for module in PLUGIN_MODULES}
    modules.update({f"panamage-jxl-natives-{platform}": f"jars/{directory}"
                    for platform, directory in PLATFORM_DIRS.items()})
    files = {}
    for module, directory in modules.items():
        name = f"{module}-{version}.jar"
        source = PROJECT_DIR / module / "target" / name if from_target else DIST_DIR / version / name
        if not source.is_file():
            hint = "build the project with -Pjdk21" if from_target else "run make_release.py or use --from-target"
            raise SystemExit(f"{source} not found; {hint}")
        files[f"{directory}/{name}"] = source
    files[LAUNCHER_FILE] = LAUNCHER_SOURCE
    return files


def fiji_java(fiji: Path) -> Path:
    """Returns the java executable of the Java 21 that comes with Fiji."""
    for release in sorted((fiji / "java").rglob("release")):
        text = release.read_text(encoding="utf-8", errors="replace")
        match = re.search(r'^JAVA_VERSION="(\d+)', text, re.MULTILINE)
        if match and match.group(1) == JAVA_RELEASE:
            for name in ("java.exe", "java"):
                java = release.parent / "bin" / name
                if java.is_file():
                    return java
    raise SystemExit(f"No Java {JAVA_RELEASE} found in {fiji / 'java'}; the plugin needs Fiji with Java "
                     f"{JAVA_RELEASE} (Fiji \"latest\")")


def remove_old_files(fiji: Path) -> None:
    for pattern in ("jars/panamage-jxl-*.jar", "jars/*/panamage-jxl-natives-*.jar"):
        for path in fiji.glob(pattern):
            print(f"Removing {path.relative_to(fiji).as_posix()}")
            path.unlink()


def copy_files(fiji: Path, files: dict[str, Path]) -> None:
    for target, source in files.items():
        path = fiji / target
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, path)
        print(f"Copied {target}")


def updater(java: Path, fiji: Path, args: list[str], interactive: bool = False, quiet: bool = False) -> str:
    """Runs the Fiji updater; it may ask for a password only if interactive, and quiet hides its output."""
    command = [str(java), f"-Dij.dir={fiji}", "-cp", str(fiji / "jars" / "*"), "net.imagej.updater.CommandLine",
               *args]
    print(f"> updater {' '.join(args)}", flush=True)
    if interactive:
        result = subprocess.run(command, cwd=fiji)
        output = ""
    else:
        result = subprocess.run(command, cwd=fiji, stdin=subprocess.DEVNULL, capture_output=True, text=True)
        output = result.stdout + result.stderr
        if not quiet or result.returncode != 0:
            print(output, end="")
    if result.returncode != 0:
        raise SystemExit(f"The Fiji updater failed with exit code {result.returncode}")
    return output


def ensure_site(java: Path, fiji: Path, name: str, url: str, host: str, directory: str) -> None:
    """Adds the update site to the Fiji installation, or updates its URL and upload settings."""
    # Asked for an unknown name, list-update-sites fails, so it lists all sites.
    sites = updater(java, fiji, ["list-update-sites"], quiet=True)
    known = any(re.match(rf"{re.escape(name)}( \(DISABLED\))?:", line) for line in sites.splitlines())
    updater(java, fiji, ["edit-update-site" if known else "add-update-site", name, url, host, directory])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--fiji", type=Path, required=True, help="the Fiji installation to upload from")
    parser.add_argument("--version", help="the version to upload (default: the version in pom.xml)")
    parser.add_argument("--from-target", action="store_true",
                        help="take the JARs from the target directories instead of dist/<version>")
    parser.add_argument("--simulate", action="store_true", help="only show what would be uploaded")
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--site", help="the name of the personal update site on sites.imagej.net")
    target.add_argument("--local-site", type=Path, help="a directory for a local test site")
    parser.add_argument("--webdav-user", help="the WebDAV user of the site on sites.imagej.net")
    args = parser.parse_args()
    if args.site and not args.webdav_user:
        parser.error("--site needs --webdav-user")

    fiji = args.fiji.resolve()
    if not list((fiji / "jars").glob("imagej-updater-*.jar")):
        raise SystemExit(f"{fiji} is not a Fiji installation (no jars/imagej-updater-*.jar)")
    java = fiji_java(fiji)
    version = args.version or pom_values()[0]
    if args.site and version.endswith("-SNAPSHOT") and not args.simulate:
        raise SystemExit(f"Version {version} is a snapshot; upload snapshots only to a --local-site")
    files = site_files(version, args.from_target)

    remove_old_files(fiji)
    copy_files(fiji, files)

    if args.local_site:
        site = args.local_site.resolve()
        site.mkdir(parents=True, exist_ok=True)
        name = LOCAL_SITE_NAME
        directory = site.as_posix() + "/"
        ensure_site(java, fiji, name, site.as_uri() + "/", "file:localhost", directory)
    else:
        name = args.site
        ensure_site(java, fiji, name, SITES_URL.format(name=name), f"webdav:{args.webdav_user}", "")

    upload = ["upload", *(["--simulate"] if args.simulate else []), "--update-site", name, *files]
    updater(java, fiji, upload, interactive=not args.local_site)
    print(f"\n{'Simulated upload' if args.simulate else 'Uploaded'} {len(files)} files of panamage {version} "
          f"to the update site {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
