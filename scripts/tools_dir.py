"""Location of the external tools (jextract, libjxl, Linux JDK) used by the scripts.

Resolution order, the same as in the Maven build:
  1. an explicit path (a command line option of the calling script)
  2. the PANAMAGE_TOOLS_DIR environment variable
  3. the .tools directory in the project
"""

import os
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent.parent
ENVIRONMENT_VARIABLE = "PANAMAGE_TOOLS_DIR"
DEFAULT_TOOLS_DIR = PROJECT_DIR / ".tools"


def tools_dir(explicit: Path | None = None) -> Path:
    """Returns the tools directory as an absolute path."""
    if explicit is not None:
        return explicit.resolve()
    configured = os.environ.get(ENVIRONMENT_VARIABLE, "").strip()
    if configured:
        return Path(configured).resolve()
    return DEFAULT_TOOLS_DIR
