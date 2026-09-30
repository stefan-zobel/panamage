"""Write a Maven toolchains file that provides a JDK 25.

The build selects the JDK through the Maven toolchains plugin, so it needs a
toolchains file with a JDK 25 entry. Continuous integration writes one for the
JDK of the runner; wsl_verify.py writes one for the Linux JDK in WSL.

Usage:
  python write_toolchains.py --jdk JDK_HOME [--output FILE]

The output defaults to ~/.m2/toolchains.xml, which is replaced.
"""

import argparse
import sys
from pathlib import Path
from xml.sax.saxutils import escape

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


def write_toolchains(jdk_home: str, output: Path) -> None:
    """Writes a toolchains file with a single JDK 25 at jdk_home."""
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(TOOLCHAINS.format(jdk_home=escape(jdk_home)), encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--jdk", required=True, help="home directory of the JDK 25")
    parser.add_argument("--output", type=Path, default=Path.home() / ".m2" / "toolchains.xml",
                        help="toolchains file to write (default: ~/.m2/toolchains.xml)")
    args = parser.parse_args()
    if not (Path(args.jdk) / "bin").is_dir():
        raise SystemExit(f"Not a JDK home directory: {args.jdk}")
    write_toolchains(args.jdk, args.output)
    print(f"Wrote {args.output} for the JDK at {args.jdk}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
