#!/usr/bin/env python3
"""Integrity helpers for NoTouch calibration-comparison evaluation sessions (log schema 4). Python standard library only.

  export --adb <adb> --serial <s> --out <incoming-dir>
                                      read-only copy of every session on the phone (adb exec-out run-as ... tar) into
                                      <incoming-dir>/export-<UTC>.tar, extracted beside it with strict path checks,
                                      then verified. Never deletes anything on the phone
  verify  <dir>                       check each session's SHA256SUMS (written on the phone at finalization)
  import  <extracted-dir> --raw <dir> copy sessions byte for byte to <raw>/<calibration_id>/<participant_id>/<session_id>/;
                                      never overwrites; refuses sessions whose SHA256SUMS fail, other schemas and
                                      undeclared calibration IDs; re-importing identical files is a no-op. Technical-pilot
                                      sessions (participant IDs starting with PILOT) are refused unless --allow-pilot is
                                      given, and --allow-pilot refuses every other session, so pilot and official
                                      sessions never share a destination
  freeze  <raw-dir> --out <file>      write a SHA-256 manifest of every raw file (freeze before official analysis)
  check-frozen <raw-dir> <file>       confirm the raw files still match a frozen manifest

Session identity comes from metadata.json, not from folder names. Raw files are only ever read or copied.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import shutil
import subprocess
import sys
import tarfile
from datetime import datetime, timezone
from pathlib import Path

SESSION_FILES = ("SHA256SUMS", "attempts.csv", "events.csv", "metadata.json", "model_snapshot.json")
IDENTIFIER = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
SESSION_ID = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")
PILOT_PREFIX = "PILOT"
SCHEMA_VERSION = 4
APPLICATION_ID = "dev.gazedot"
DEVICE_ROOT = "files/evaluation/calibration-comparison"
DEFAULT_PROTOCOL = Path(__file__).resolve().parents[1] / "protocol.json"
EXPORT_MEMBER = re.compile(r"^files/evaluation/calibration-comparison/([A-Za-z0-9_-]{1,32})/([A-Za-z0-9_.-]{1,64})/"
                           r"(SHA256SUMS|attempts\.csv|events\.csv|metadata\.json|model_snapshot\.json)$")
EXPORT_DIRECTORY = re.compile(r"^files/evaluation/calibration-comparison(/[A-Za-z0-9_-]{1,32}(/[A-Za-z0-9_.-]{1,64})?)?/?$")
EXPORT_TEMPORARY = re.compile(r"^files/evaluation/calibration-comparison/[A-Za-z0-9_-]{1,32}/[A-Za-z0-9_.-]{1,64}/[A-Za-z0-9_.]+\.tmp$")


def allowed_calibration_ids(protocol: Path = DEFAULT_PROTOCOL) -> tuple[str, ...]:
    return tuple(json.loads(protocol.read_text(encoding="utf-8"))["candidates"]["allowed_calibration_ids"])


def is_pilot(participant_id: str) -> bool:
    return participant_id.upper().startswith(PILOT_PREFIX)


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


def identity(session: Path, allowed: tuple[str, ...]) -> tuple[str, str, str]:
    """(calibration_id, participant_id, session_id) from metadata.json, checked against the folder and the protocol."""
    meta = json.loads((session / "metadata.json").read_text(encoding="utf-8"))
    pid, sid = meta.get("participant_id"), meta.get("session_id")
    if not isinstance(pid, str) or not IDENTIFIER.match(pid) or not isinstance(sid, str) or not SESSION_ID.match(sid):
        raise ValueError(f"{session}: metadata has no valid participant_id/session_id")
    if session.name != sid:
        raise ValueError(f"{session}: folder name differs from metadata session_id {sid}")
    if meta.get("schema_version") != SCHEMA_VERSION:
        raise ValueError(f"{session}: schema_version {meta.get('schema_version')!r} is not {SCHEMA_VERSION} "
                         "(Iteration 3 schema 3 sessions belong to their own dataset)")
    cid = (meta.get("calibration") or {}).get("calibration_id") if isinstance(meta.get("calibration"), dict) else None
    if cid not in allowed:
        raise ValueError(f"{session}: calibration_id {cid!r} is not a declared candidate {list(allowed)}")
    return cid, pid, sid


def import_sessions(source: Path, raw: Path, allow_pilot: bool = False, protocol: Path = DEFAULT_PROTOCOL) -> list[str]:
    """Copies each session to raw/<calibration_id>/<participant>/<session>. Returns log lines; raises on any conflict."""
    allowed = allowed_calibration_ids(protocol)
    log = []
    sessions = [(s, *identity(s, allowed)) for s in sessions_under(source)]
    for session, _, pid, _ in sessions:
        if is_pilot(pid) != allow_pilot:
            raise ValueError(f"{session}: participant {pid} is {'a' if is_pilot(pid) else 'not a'} technical-pilot ID; "
                             + ("import pilot sessions only with --allow-pilot into data/pilot/" if is_pilot(pid)
                                else "--allow-pilot imports PILOT* sessions only"))
    for session, cid, pid, sid in sessions:
        problems = check_sums(session)
        if problems and (session / "SHA256SUMS").is_file():
            raise ValueError(f"{session}: {'; '.join(problems)}; export again from the phone instead of importing")
        files = [n for n in SESSION_FILES if (session / n).is_file()]
        extra = sorted(p.name for p in session.iterdir() if p.name not in SESSION_FILES)
        dest = raw / cid / pid / sid
        if dest.exists():
            existing = sorted(p.name for p in dest.iterdir())
            if existing == sorted(files) and all(sha256(dest / n) == sha256(session / n) for n in files):
                log.append(f"unchanged {cid}/{pid}/{sid} (already imported)")
                continue
            raise ValueError(f"{dest} already exists with different content; refusing to overwrite raw data")
        staging = raw / cid / pid / f".{sid}.importing"
        if staging.exists():
            shutil.rmtree(staging)
        staging.mkdir(parents=True)
        for name in files:
            shutil.copyfile(session / name, staging / name)
            if sha256(staging / name) != sha256(session / name):
                raise OSError(f"copy of {name} differs from its source")
        staging.rename(dest)
        note = "checksums verified" if not problems else "WARNING " + "; ".join(problems) + " (interrupted session)"
        log.append(f"imported {cid}/{pid}/{sid}: {len(files)} files, {note}" + (f"; skipped {extra}" if extra else ""))
    return log


def extract_export(archive: bytes, dest: Path) -> list[str]:
    """Extracts an app-storage tar into [dest] after validating every member; returns log lines.

    Only regular files of the known session layout are written. Links, devices, absolute or parent paths and unknown
    files refuse the whole archive; temporary files of an interrupted session are skipped and listed.
    """
    if dest.exists():
        raise ValueError(f"{dest} already exists; refusing to extract over it")
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tar:
        members = tar.getmembers()
        files, skipped = [], []
        for m in members:
            name = m.name[2:] if m.name.startswith("./") else m.name
            if m.isdir() and EXPORT_DIRECTORY.match(name):
                continue
            if m.isfile() and EXPORT_TEMPORARY.match(name):
                skipped.append(name)
                continue
            if not m.isfile() or not EXPORT_MEMBER.match(name):
                raise ValueError(f"unexpected archive member {m.name!r}; nothing extracted")
            files.append((name, m))
        if not files:
            raise ValueError("the archive holds no session files")
        dest.mkdir(parents=True)
        for name, m in files:
            target = dest / name
            target.parent.mkdir(parents=True, exist_ok=True)
            data = tar.extractfile(m).read()
            target.write_bytes(data)
    return [f"extracted {len(files)} files" + (f"; skipped temporary files {skipped}" if skipped else "")]


def export_from_phone(adb: str, serial: str, out: Path, package: str = APPLICATION_ID) -> tuple[Path, list[str]]:
    """Read-only: streams the app's evaluation folder from the phone as tar, keeps the archive and extracts it."""
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    archive = out / f"export-{stamp}.tar"
    folder = out / f"export-{stamp}"
    if archive.exists() or folder.exists():
        raise ValueError(f"{archive} or {folder} already exists")
    result = subprocess.run([adb, "-s", serial, "exec-out", "run-as", package, "tar", "-cf", "-", DEVICE_ROOT],
                            capture_output=True, check=False)
    if result.returncode != 0 or not result.stdout:
        raise OSError(f"adb export failed (exit {result.returncode}): {result.stderr.decode('utf-8', 'replace').strip()}")
    out.mkdir(parents=True, exist_ok=True)
    archive.write_bytes(result.stdout)
    (out / f"{archive.name}.sha256").write_text(f"{hashlib.sha256(result.stdout).hexdigest()}  {archive.name}\n", encoding="utf-8")
    log = [f"archive {archive} ({len(result.stdout)} bytes, sha256 {hashlib.sha256(result.stdout).hexdigest()})"]
    log += extract_export(result.stdout, folder)
    return folder, log


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


def _verify(root: Path) -> int:
    bad = 0
    sessions = sessions_under(root)
    for session in sessions:
        problems = check_sums(session)
        bad += bool(problems)
        print(f"{'OK  ' if not problems else 'FAIL'} {session.relative_to(root).as_posix() or session.name}"
              + ("" if not problems else ": " + "; ".join(problems)))
    if not sessions:
        print(f"no sessions under {root}")
    return 1 if bad else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    e = sub.add_parser("export"); e.add_argument("--adb", required=True); e.add_argument("--serial", required=True)
    e.add_argument("--out", type=Path, required=True); e.add_argument("--package", default=APPLICATION_ID)
    v = sub.add_parser("verify"); v.add_argument("dir", type=Path)
    i = sub.add_parser("import"); i.add_argument("dir", type=Path); i.add_argument("--raw", type=Path, required=True)
    i.add_argument("--allow-pilot", action="store_true", help="import technical-pilot (PILOT*) sessions only")
    i.add_argument("--protocol", type=Path, default=DEFAULT_PROTOCOL)
    f = sub.add_parser("freeze"); f.add_argument("raw", type=Path); f.add_argument("--out", type=Path, required=True)
    c = sub.add_parser("check-frozen"); c.add_argument("raw", type=Path); c.add_argument("manifest", type=Path)
    args = parser.parse_args(argv)
    if args.command == "export":
        try:
            folder, log = export_from_phone(args.adb, args.serial, args.out, args.package)
        except (ValueError, OSError, tarfile.TarError) as err:
            print(f"error: {err}", file=sys.stderr)
            return 1
        for line in log:
            print(line)
        return _verify(folder)
    if args.command == "verify":
        return _verify(args.dir)
    if args.command == "import":
        try:
            for line in import_sessions(args.dir, args.raw, args.allow_pilot, args.protocol):
                print(line)
        except (ValueError, OSError) as err:
            print(f"error: {err}", file=sys.stderr)
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
