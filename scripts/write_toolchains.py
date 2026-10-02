"""Write a Maven toolchains file that provides a JDK 25, and optionally a JDK 21.

The build selects the JDK through the Maven toolchains plugin, so it needs a
toolchains file with a JDK 25 entry, and with a JDK 21 entry for
panamage-jxl-jdk21 (profile jdk21). Continuous integration writes one for the
JDKs of the runner; wsl_verify.py writes one for the Linux JDK in WSL.

Usage:
  python write_toolchains.py --jdk JDK_HOME [--jdk21 JDK_HOME] [--output FILE]

The output defaults to ~/.m2/toolchains.xml, which is replaced.
"""

import argparse
import sys
from pathlib import Path
from xml.sax.saxutils import escape

TOOLCHAIN = """  <toolchain>
    <type>jdk</type>
    <provides>
      <version>{version}</version>
    </provides>
    <configuration>
      <jdkHome>{jdk_home}</jdkHome>
    </configuration>
  </toolchain>
"""


def write_toolchains(jdks: dict[str, str], output: Path) -> None:
    """Writes a toolchains file with one JDK per version, {version: home directory}."""
    entries = "".join(TOOLCHAIN.format(version=version, jdk_home=escape(home)) for version, home in jdks.items())
    text = f'<?xml version="1.0" encoding="UTF-8"?>\n<toolchains>\n{entries}</toolchains>\n'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--jdk", required=True, help="home directory of the JDK 25")
    parser.add_argument("--jdk21", help="home directory of the JDK 21, for panamage-jxl-jdk21")
    parser.add_argument("--output", type=Path, default=Path.home() / ".m2" / "toolchains.xml",
                        help="toolchains file to write (default: ~/.m2/toolchains.xml)")
    args = parser.parse_args()
    jdks = {"25": args.jdk}
    if args.jdk21 is not None:
        jdks["21"] = args.jdk21
    for home in jdks.values():
        if not (Path(home) / "bin").is_dir():
            raise SystemExit(f"Not a JDK home directory: {home}")
    write_toolchains(jdks, args.output)
    print(f"Wrote {args.output} for the JDK " + " and ".join(f"{v} at {h}" for v, h in jdks.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
