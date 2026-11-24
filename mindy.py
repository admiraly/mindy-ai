"""Build and launch Mindy clients with the pinned Mindustry desktop release."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.request import urlretrieve
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
VERSION = "v160.8"
GAME = BUILD / f"Mindustry-{VERSION}.jar"
GAME_SHA256 = "6dd6da921d5021a84823456e05b477ee8de8511990d40de850b37dd0905152f2"
MOD = BUILD / "mindy-ai.jar"


def verified_game():
    BUILD.mkdir(exist_ok=True)
    if not GAME.exists():
        print(f"Downloading Mindustry {VERSION}...", flush=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as temp:
            download = Path(temp) / GAME.name
            urlretrieve(
                f"https://github.com/Anuken/Mindustry/releases/download/{VERSION}/Mindustry.jar",
                download,
            )
            verify_digest(download)
            download.replace(GAME)
    else:
        verify_digest(GAME)
    return GAME


def verify_digest(path):
    with path.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != GAME_SHA256:
            raise ValueError(f"Unexpected SHA-256 for {path}. Delete it and download again.")


def java_tool(name):
    java_home = os.getenv("JAVA_HOME")
    if java_home:
        filename = f"{name}.exe" if os.name == "nt" else name
        executable = Path(java_home) / "bin" / filename
        if executable.is_file():
            return str(executable)
    executable = shutil.which(name)
    if executable is None:
        raise ValueError(f"Install JDK 17 or newer and set JAVA_HOME or add {name} to PATH.")
    return executable


def build():
    game = verified_game()
    sources = sorted((ROOT / "src/main/java").rglob("*.java"))
    resources = ROOT / "src/main/resources"
    with tempfile.TemporaryDirectory(dir=BUILD) as temp:
        work = Path(temp)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run(
            [java_tool("javac"), "--release", "17", "-encoding", "UTF-8", "-cp", game, "-d", classes, *sources],
            check=True,
        )
        archive = work / MOD.name
        with ZipFile(archive, "w", ZIP_DEFLATED) as jar:
            for directory in (classes, resources):
                for file in sorted(directory.rglob("*")):
                    if file.is_file():
                        jar.write(file, file.relative_to(directory).as_posix())
        archive.replace(MOD)
    print(f"Built {MOD}", flush=True)


def run_bots(args):
    java = java_tool("java")
    game = verified_game()
    if not MOD.is_file():
        raise ValueError("Build the bot first with python mindy.py build.")

    logs = Path(tempfile.mkdtemp(prefix="run-", dir=BUILD))
    clients = []
    try:
        for name in args.bots:
            profile = logs / name
            mods = profile / "mods"
            mods.mkdir(parents=True)
            shutil.copyfile(MOD, mods / MOD.name)
            command = [java]
            if os.sys.platform == "darwin":
                command.append("-XstartOnFirstThread")
            command.extend([
                f"-Dmindustry.data.dir={profile}",
                f"-Dmindy.host={args.host}",
                f"-Dmindy.port={args.port}",
                f"-Dmindy.name={name}",
                f"-Dmindy.owner={args.owner}",
                "-jar", game,
            ])
            with (profile / "console.log").open("wb") as log:
                client = subprocess.Popen(command, cwd=profile, stdout=log, stderr=subprocess.STDOUT)
            clients.append((name, client))
            print(f"Started {name}. Log {profile / 'console.log'}", flush=True)

        failed = False
        while clients:
            _, client = clients[0]
            try:
                client.wait(timeout=1)
            except subprocess.TimeoutExpired:
                pass
            for name, client in clients[:]:
                if client.poll() is not None:
                    print(f"{name} exited with code {client.returncode}.", flush=True)
                    failed |= client.returncode != 0
                    clients.remove((name, client))
        return int(failed)
    except KeyboardInterrupt:
        return 130
    finally:
        for _, client in clients:
            if client.poll() is None:
                client.terminate()
        for _, client in clients:
            client.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    actions.add_parser("build", help="download Mindustry and build the client mod")
    run = actions.add_parser("run", help="join a private session with one or more bots")
    run.add_argument("--host", required=True, help="host IP address or hostname")
    run.add_argument("--port", type=int, default=6567)
    run.add_argument("--owner", required=True, help="your exact in-game name without color codes")
    run.add_argument("--bots", nargs="+", default=["Mindy"], help="bot names (default: one bot named Mindy)")
    args = parser.parse_args()
    if args.action == "run":
        folded = {name.casefold() for name in args.bots}
        if (
            not 1 <= args.port <= 65535
            or not args.owner.strip()
            or any(not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,19}", name) for name in args.bots)
            or len(folded) != len(args.bots)
            or "all" in folded
            or args.owner.casefold() in folded
        ):
            run.error("Use a valid port, your in-game name, and distinct bot names of 1–20 characters. "
                      "Bot names must start with a letter, use only letters, digits, _ or -, and cannot be 'all'.")
    try:
        if args.action == "build":
            build()
            return 0
        return run_bots(args)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
