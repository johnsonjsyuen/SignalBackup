#!/usr/bin/env python3
"""Turn errors in a Gradle log into GitHub ::error:: annotations.

Usage: annotate-gradle-errors.py <log file> <step title>

GitHub shows at most 10 error annotations per step, so the first 9 findings get their own
annotation (with file/line when known) and the rest are folded into a single multi-line one.
"""
import os
import re
import sys

MAX_SINGLE = 9
MAX_FOLDED_LINES = 80

KOTLIN = re.compile(r"^e: (?:file://)?(?P<file>/[^:]+\.kts?):(?P<line>\d+):(?P<col>\d+) (?P<msg>.*)$")
KOTLIN_PLAIN = re.compile(r"^e: (?P<msg>.*)$")
JAVAC = re.compile(r"^(?P<file>/[^:]+\.(?:java|kt|xml)):(?P<line>\d+): error: (?P<msg>.*)$")
LINT = re.compile(r"^(?P<file>[^\s:]+\.(?:kt|java|xml|kts|toml)):(?P<line>\d+): Error: (?P<msg>.*)$")
LINT_NOLINE = re.compile(r"^(?P<file>[^\s:]+\.(?:kt|java|xml|kts|toml)): Error: (?P<msg>.*)$")
TEST_FAILED = re.compile(r"^(?P<test>\S.* > .+) FAILED$")
GENERIC = re.compile(r"(error:|ERROR:|\[ksp\].*error)", re.IGNORECASE)


def esc_data(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def esc_prop(s: str) -> str:
    return esc_data(s).replace(":", "%3A").replace(",", "%2C")


def rel(path: str) -> str:
    ws = os.environ.get("GITHUB_WORKSPACE", "")
    if ws and path.startswith(ws.rstrip("/") + "/"):
        return path[len(ws.rstrip("/")) + 1:]
    if path.startswith(("src/", "lint.xml")):  # lint prints module-relative paths
        return "app/" + path
    return path


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: annotate-gradle-errors.py <log> <title>", file=sys.stderr)
        return 2
    log_path, title = sys.argv[1], sys.argv[2]
    try:
        with open(log_path, encoding="utf-8", errors="replace") as f:
            lines = [l.rstrip("\n") for l in f]
    except OSError as e:
        print(f"::error title={esc_prop(title)}::No log to annotate ({e})")
        return 0

    findings = []  # (props dict, message)
    seen = set()

    def add(msg, file=None, line=None, col=None):
        key = (file, line, msg)
        if key in seen or not msg.strip():
            return
        seen.add(key)
        props = {}
        if file:
            props["file"] = rel(file)
        if line:
            props["line"] = line
        if col:
            props["col"] = col
        findings.append((props, msg.strip()))

    i = 0
    while i < len(lines):
        ln = lines[i]
        m = KOTLIN.match(ln)
        if m:
            add(m["msg"], m["file"], m["line"], m["col"])
        elif (m := KOTLIN_PLAIN.match(ln)):
            add(m["msg"])
        elif (m := JAVAC.match(ln)):
            add(m["msg"], m["file"], m["line"])
        elif (m := LINT.match(ln)):
            add(f"Lint: {m['msg']}", m["file"], m["line"])
        elif (m := LINT_NOLINE.match(ln)):
            add(f"Lint: {m['msg']}", m["file"])
        elif (m := TEST_FAILED.match(ln)):
            detail = []
            j = i + 1
            while j < len(lines) and lines[j].startswith((" ", "\t")) and len(detail) < 4:
                if lines[j].strip():
                    detail.append(lines[j].strip())
                j += 1
            add(f"Test FAILED: {m['test']}" + ("\n" + "\n".join(detail) if detail else ""))
        elif ln.startswith("* What went wrong"):
            block = []
            j = i + 1
            while j < len(lines) and not lines[j].startswith("* Try") and len(block) < 15:
                if lines[j].strip():
                    block.append(lines[j].strip())
                j += 1
            add("What went wrong: " + "\n".join(block))
            i = j
            continue
        elif GENERIC.search(ln) and not ln.startswith("w: "):
            add(ln)
        i += 1

    if not findings:
        tail = "\n".join(l for l in lines[-25:] if l.strip())
        findings.append(({}, "No recognised error lines; log tail:\n" + tail))

    for props, msg in findings[:MAX_SINGLE]:
        props_str = ",".join(f"{k}={esc_prop(str(v))}" for k, v in props.items())
        head = f"title={esc_prop(title)}" + (f",{props_str}" if props_str else "")
        print(f"::error {head}::{esc_data(msg)}")

    rest = findings[MAX_SINGLE:]
    if rest:
        folded = []
        for props, msg in rest:
            loc = props.get("file", "")
            if "line" in props:
                loc += f":{props['line']}"
            folded.append((f"{loc}: " if loc else "") + msg.replace("\n", " | "))
        body = "\n".join(folded[:MAX_FOLDED_LINES])
        if len(folded) > MAX_FOLDED_LINES:
            body += f"\n... and {len(folded) - MAX_FOLDED_LINES} more"
        print(f"::error title={esc_prop(title + ' (more)')}::{esc_data(body)}")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as s:
            s.write(f"### {title} failed\n\n```\n")
            for props, msg in findings[:200]:
                loc = props.get("file", "")
                if "line" in props:
                    loc += f":{props['line']}"
                s.write((f"{loc}: " if loc else "") + msg + "\n")
            s.write("```\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
