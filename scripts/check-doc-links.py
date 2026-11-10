"""Check local links in maintained entry points (no network or historical source URLs)."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
files = [root / name for name in ("README.md", "AGENTS.md", "docs/API_BOUNDARY.md")]
failures = []
count = 0
for path in files:
    for link in re.findall(r"\[[^\]]*\]\(([^)]+)\)", path.read_text(encoding="utf-8")):
        if "://" in link or link.startswith("#"):
            continue
        target = link.split("#", 1)[0]
        count += 1
        if not (path.parent / target).exists():
            failures.append(f"{path.relative_to(root)}: {target}")
if failures:
    raise SystemExit("Missing local documentation links:\n" + "\n".join(failures))
print(f"DOC_LINKS_PASSED links={count} files={len(files)}")
