#!/usr/bin/env python3
"""Integrity helpers for NoTouch Iteration 3 evaluation sessions. Python standard library only.

  verify  <dir>                       check each session's SHA256SUMS (written on the phone at finalization)
  import  <extracted-dir> --raw <dir> copy sessions byte for byte to <raw>/<participant_id>/<session_id>/;
                                      never overwrites; refuses sessions whose SHA256SUMS fail;
                                      re-importing identical files is a no-op
  freeze  <raw-dir> --out <file>      write a SHA-256 manifest of every raw file (freeze before official analysis)
  check-frozen <raw-dir> <file>       confirm the raw files still match a frozen manifest

Session identity comes from metadata.json, not from folder names. Raw files are only ever read or copied.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

SESSION_FILES = ("SHA256SUMS", "attempts.csv", "events.csv", "metadata.json", "model_snapshot.json")
IDENTIFIER = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
SESSION_ID = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sessions_under(root: Path) -> list[Path]:
    if (root / "metadata.json").is_file():
        return [root]
    return [p for child in sorted(d for d in root.iterdir() if d.is_dir()) for p in sessions_under(child)]


def check_sums(session: Path) -> list[str]:
    """Problems with a session's SHA256SUMS; empty when every listed file matches."""
    sums = session / "SHA256SUMS"
    if not sums.is_file():
        return ["no SHA256SUMS (session was not finalized on the phone)"]
    problems = []
    for line in sums.read_text(encoding="utf-8").splitlines():
        digest, _, name = line.partition("  ")
        target = session / name
        if not re.fullmatch(r"[0-9a-f]{64}", digest) or "/" in name or "\\" in name:
            problems.append(f"unreadable line {line!r}")
        elif not target.is_file():
            problems.append(f"{name} missing")
        elif sha256(target) != digest:
            problems.append(f"{name} changed after recording")
    return problems


def identity(session: Path) -> tuple[str, str]:
    meta = json.loads((session / "metadata.json").read_text(encoding="utf-8"))
    pid, sid = meta.get("participant_id"), meta.get("session_id")
    if not isinstance(pid, str) or not IDENTIFIER.match(pid) or not isinstance(sid, str) or not SESSION_ID.match(sid):
        raise ValueError(f"{session}: metadata has no valid participant_id/session_id")
    if session.name != sid:
        raise ValueError(f"{session}: folder name differs from metadata session_id {sid}")
    return pid, sid


def import_sessions(source: Path, raw: Path) -> list[str]:
    """Copies each session to raw/<participant>/<session>. Returns log lines; raises on any conflict."""
    log = []
    for session in sessions_under(source):
        pid, sid = identity(session)
        problems = check_sums(session)
        if problems and (session / "SHA256SUMS").is_file():
            raise ValueError(f"{session}: {'; '.join(problems)}; export again from the phone instead of importing")
        files = [n for n in SESSION_FILES if (session / n).is_file()]
        extra = sorted(p.name for p in session.iterdir() if p.name not in SESSION_FILES)
        dest = raw / pid / sid
        if dest.exists():
            existing = sorted(p.name for p in dest.iterdir())
            if existing == sorted(files) and all(sha256(dest / n) == sha256(session / n) for n in files):
                log.append(f"unchanged {pid}/{sid} (already imported)")
                continue
            raise ValueError(f"{dest} already exists with different content; refusing to overwrite raw data")
        staging = raw / pid / f".{sid}.importing"
        if staging.exists():
            shutil.rmtree(staging)
        staging.mkdir(parents=True)
        for name in files:
            shutil.copyfile(session / name, staging / name)
            if sha256(staging / name) != sha256(session / name):
                raise OSError(f"copy of {name} differs from its source")
        staging.rename(dest)
        note = "checksums verified" if not problems else "WARNING " + "; ".join(problems) + " (interrupted run)"
        log.append(f"imported {pid}/{sid}: {len(files)} files, {note}" + (f"; skipped {extra}" if extra else ""))
    return log


def freeze(raw: Path) -> str:
    files = sorted(p for p in raw.rglob("*") if p.is_file() and p.name != "README.md")
    return "".join(f"{sha256(p)}  {p.relative_to(raw).as_posix()}\n" for p in files)


def check_frozen(raw: Path, manifest: Path) -> list[str]:
    expected = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        digest, _, name = line.partition("  ")
        expected[name] = digest
    actual = {line.partition("  ")[2]: line.partition("  ")[0] for line in freeze(raw).splitlines()}
    problems = [f"{n} missing" for n in sorted(set(expected) - set(actual))]
    problems += [f"{n} changed" for n in sorted(set(expected) & set(actual)) if expected[n] != actual[n]]
    problems += [f"{n} not in the frozen manifest (added later)" for n in sorted(set(actual) - set(expected))]
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    v = sub.add_parser("verify"); v.add_argument("dir", type=Path)
    i = sub.add_parser("import"); i.add_argument("dir", type=Path); i.add_argument("--raw", type=Path, required=True)
    f = sub.add_parser("freeze"); f.add_argument("raw", type=Path); f.add_argument("--out", type=Path, required=True)
    c = sub.add_parser("check-frozen"); c.add_argument("raw", type=Path); c.add_argument("manifest", type=Path)
    args = parser.parse_args(argv)
    if args.command == "verify":
        bad = 0
        for session in sessions_under(args.dir):
            problems = check_sums(session)
            bad += bool(problems)
            print(f"{'OK  ' if not problems else 'FAIL'} {session.relative_to(args.dir).as_posix() or session.name}"
                  + ("" if not problems else ": " + "; ".join(problems)))
        return 1 if bad else 0
    if args.command == "import":
        try:
            for line in import_sessions(args.dir, args.raw):
                print(line)
        except (ValueError, OSError) as e:
            print(f"error: {e}", file=sys.stderr)
            return 1
        return 0
    if args.command == "freeze":
        if args.out.resolve().is_relative_to(args.raw.resolve()):
            parser.error("--out must be outside the raw directory")
        with open(args.out, "w", encoding="utf-8", newline="\n") as out:
            out.write(freeze(args.raw))
        return 0
    problems = check_frozen(args.raw, args.manifest)
    for p in problems:
        print(p)
    print("frozen inputs unchanged" if not problems else f"{len(problems)} difference(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
