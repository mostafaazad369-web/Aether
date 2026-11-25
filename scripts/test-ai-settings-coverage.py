#!/usr/bin/env python3
"""Regression guard for the retired in-app AI assistant.

The VPN remains independent of external AI providers. This check prevents the
removed assistant runtime, navigation and contextual-help UI from reappearing.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/main/java"
FORBIDDEN = (
    "AiSession", "GeminiClient", "GeminiHttp", "SettingsRoute.AI",
    "AiTopic", "aiTopic =", "AiTopicIcon", "LocalAiHost", "AiAdvisorPage", "AiChatScreen",
    "R.string.ai_",
)

def main():
    failures = []
    for path in SOURCE.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        for marker in FORBIDDEN:
            if marker in text:
                failures.append(f"{path.relative_to(ROOT)} still references {marker}")
    for directory in (ROOT / "app/src/main/res/values", ROOT / "app/src/main/res/values-fa"):
        xml = directory / "strings.xml"
        if 'name="ai_' in xml.read_text(encoding="utf-8"):
            failures.append(f"{xml.relative_to(ROOT)} still contains AI strings")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("Retired AI assistant removal guard passed.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
