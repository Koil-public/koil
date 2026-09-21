#!/usr/bin/env python3
"""
Import Agent Skills repositories into Koil's user skill directory.

The importer copies only skill folders containing SKILL.md plus their bundled
resources. It does not execute any imported script. Existing installed skills
are replaced atomically per source when --update is used.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

DEFAULT_REPOS = [
    "anthropics/skills",
    "addyosmani/agent-skills",
    "sickn33/agentic-awesome-skills",
    "mattpocock/skills",
    "microsoft/skills",
    "obra/superpowers",
    "google/skills",
    "K-Dense-AI/scientific-agent-skills",
    "huggingface/skills",
    "VoltAgent/awesome-agent-skills",
    "obra/superpowers",
    "affaan-m/ECC",
    "Snailclimb/JavaGuide",
    "Leonxlnx/taste-skill",
    "bytedance/deer-flow",
    "mvanhorn/last30days-skill",
    "hesreallyhim/awesome-claude-code",
    "danny-avila/LibreChat",
    "mukul975/Anthropic-Cybersecurity-Skills",
    "Jahrome907/minecraft-agent-skills",
]


def run(*args: str, cwd: Path | None = None) -> str:
    proc = subprocess.run(args, cwd=cwd, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, check=False)
    if proc.returncode != 0:
        raise RuntimeError(f"command failed ({proc.returncode}): {' '.join(args)}\n{proc.stdout}")
    return proc.stdout.strip()


def normalize_repo(value: str) -> str:
    value = value.strip().rstrip("/")
    if value.startswith("https://github.com/"):
        value = value[len("https://github.com/"):]
    if value.endswith(".git"):
        value = value[:-4]
    parts = value.split("/")
    if len(parts) != 2 or not all(parts):
        raise ValueError(f"expected owner/repo or GitHub URL, got: {value}")
    return f"{parts[0]}/{parts[1]}"


def default_destination() -> Path:
    explicit = os.environ.get("KOIL_SKILLS_DIR", "").strip()
    if explicit:
        return Path(explicit).expanduser()
    # The game directory differs across launchers, so do not guess a .minecraft
    # path. A repo-local destination is deterministic and can be overridden.
    return Path.cwd() / "koil" / "user" / "skills"


def skill_dirs(repo_root: Path) -> list[Path]:
    found: list[Path] = []
    for skill_md in repo_root.rglob("SKILL.md"):
        if any(part in {".git", "node_modules", "vendor", "build", "dist"}
               for part in skill_md.parts):
            continue
        found.append(skill_md.parent)
    return sorted(set(found))


def copy_skill(source_root: Path, skill_dir: Path, destination_root: Path) -> Path:
    relative = skill_dir.relative_to(source_root)
    target = destination_root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(skill_dir, target, symlinks=False,
                    ignore=shutil.ignore_patterns(".git", "node_modules", "__pycache__", "*.pyc"))
    return target


def import_repo(repo: str, destination: Path, update: bool) -> dict:
    owner, name = repo.split("/", 1)
    source_destination = destination / "sources" / owner / name
    if source_destination.exists() and not update:
        return {"repo": repo, "status": "exists", "skills": count_installed(source_destination)}

    with tempfile.TemporaryDirectory(prefix="koil-skills-") as temporary:
        checkout = Path(temporary) / name
        run("git", "clone", "--depth", "1", "--filter=blob:none",
            f"https://github.com/{repo}.git", str(checkout))
        commit = run("git", "rev-parse", "HEAD", cwd=checkout)
        discovered = skill_dirs(checkout)

        stage = destination / ".staging" / owner / name
        if stage.exists():
            shutil.rmtree(stage)
        stage.mkdir(parents=True, exist_ok=True)
        installed: list[str] = []
        for directory in discovered:
            target = copy_skill(checkout, directory, stage)
            installed.append(str(target.relative_to(stage)))

        manifest = {
            "source": repo,
            "commit": commit,
            "skillCount": len(installed),
            "skills": installed,
            "note": "Imported content is inert until Koil selects a SKILL.md. Bundled scripts are not executed by this importer."
        }
        (stage / ".koil-source.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        source_destination.parent.mkdir(parents=True, exist_ok=True)
        if source_destination.exists():
            shutil.rmtree(source_destination)
        shutil.move(str(stage), str(source_destination))
        return {"repo": repo, "status": "imported", "commit": commit, "skills": len(installed)}


def count_installed(path: Path) -> int:
    return sum(1 for _ in path.rglob("SKILL.md")) if path.exists() else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Import Agent Skills into Koil")
    parser.add_argument("repos", nargs="*", help="GitHub owner/repo values. Defaults to Koil's curated set.")
    parser.add_argument("--dest", type=Path, default=default_destination(),
                        help="Koil skills root. Set this to <gameDir>/koil/sys/model/skills for a live install.")
    parser.add_argument("--update", action="store_true", help="Replace already-installed source snapshots.")
    parser.add_argument("--list", action="store_true", help="Only print the normalized repository set.")
    args = parser.parse_args()

    try:
        repos = []
        for raw in (args.repos or DEFAULT_REPOS):
            repo = normalize_repo(raw)
            if repo.lower() not in {item.lower() for item in repos}:
                repos.append(repo)
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 2

    if args.list:
        print("\n".join(repos))
        return 0

    destination = args.dest.expanduser().resolve()
    destination.mkdir(parents=True, exist_ok=True)
    results = []
    failed = False
    for repo in repos:
        try:
            result = import_repo(repo, destination, args.update)
            results.append(result)
            print(f"{result['status']:>8}  {repo}  skills={result['skills']}")
        except Exception as exc:  # keep other sources installable when one index/clone fails
            failed = True
            results.append({"repo": repo, "status": "failed", "error": str(exc)})
            print(f"  failed  {repo}: {exc}", file=sys.stderr)

    summary = {
        "destination": str(destination),
        "repositories": results,
        "installedSkillCount": count_installed(destination),
    }
    (destination / "import-summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"\nKoil skill root: {destination}")
    print(f"Installed SKILL.md files: {summary['installedSkillCount']}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
