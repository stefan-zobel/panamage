"""Build and test panamage on Linux x86_64 inside WSL.

Runs the Maven wrapper of the project in a WSL distribution with the Linux
JDK and native libraries unpacked by fetch_tools.py, so every test also runs
on Linux. The WSL user's Maven settings are not changed: the toolchains file
for the Linux JDK is written to the tools directory.

Usage:
  python wsl_verify.py [--distro NAME] [--tools DIR] [maven arguments ...]

Maven arguments default to "clean verify".
"""

import argparse
import shlex
import subprocess
import sys
from pathlib import Path

from tools_dir import DEFAULT_TOOLS_DIR, ENVIRONMENT_VARIABLE, PROJECT_DIR, tools_dir

LINUX_JDK_DIR = "jdk-25-linux-x86_64"
TOOLCHAINS_FILE = "wsl-toolchains.xml"

TOOLCHAINS = """<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>25</version>
    </provides>
    <configuration>
      <jdkHome>{jdk_home}</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
"""


def wsl_path(distro: str, path: Path) -> str:
    """Translate a Windows path to the path inside the WSL distribution."""
    result = subprocess.run(["wsl.exe", "-d", distro, "--exec", "wslpath", "-a", str(path)],
                            capture_output=True, text=True, check=True)
    return result.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--distro", default="Ubuntu", help="WSL distribution (default: Ubuntu)")
    parser.add_argument("--tools", type=Path, default=None,
                        help=f"tools directory of fetch_tools.py (default: ${ENVIRONMENT_VARIABLE} "
                             f"or {DEFAULT_TOOLS_DIR})")
    args, maven_args = parser.parse_known_args()
    maven_args = maven_args or ["clean", "verify"]

    tools = tools_dir(args.tools)
    jdk = tools / LINUX_JDK_DIR
    if not (jdk / "bin" / "java").is_file():
        raise SystemExit(f"Linux JDK not found at {jdk}; run fetch_tools.py first")

    jdk_linux = wsl_path(args.distro, jdk)
    toolchains = tools / TOOLCHAINS_FILE
    toolchains.write_text(TOOLCHAINS.format(jdk_home=jdk_linux), encoding="utf-8", newline="\n")

    command = " ".join([
        "cd", shlex.quote(wsl_path(args.distro, PROJECT_DIR)), "&&",
        f"JAVA_HOME={shlex.quote(jdk_linux)}",
        "./mvnw", "-B",
        "-t", shlex.quote(wsl_path(args.distro, toolchains)),
        f"-Dtools.dir={shlex.quote(wsl_path(args.distro, tools))}",
        *(shlex.quote(arg) for arg in maven_args),
    ])
    print(f"[{args.distro}] {command}", flush=True)
    return subprocess.run(["wsl.exe", "-d", args.distro, "--exec", "bash", "-lc", command]).returncode


if __name__ == "__main__":
    sys.exit(main())
