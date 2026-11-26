#!/usr/bin/env python3
"""Fail-closed Gradle checksum manifest audit and hermetic negative regressions.

The fixture creates its own tiny Maven repository. It never modifies production
verification metadata or trusts a downloaded checksum during normal CI.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
from xml.etree import ElementTree as ET
from zipfile import ZIP_DEFLATED, ZipFile
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread


ROOT = Path(__file__).resolve().parents[1]
NS = {"g": "https://schema.gradle.org/dependency-verification"}
SCHEMA = NS["g"]
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
MANIFESTS = (
    ROOT / "gradle/verification-metadata.xml",
    ROOT / "build-logic/gradle/verification-metadata.xml",
)


def audit_manifest(path: Path) -> int:
    if not path.is_file():
        raise AssertionError(f"Missing mandatory dependency verification manifest: {path}")
    root = ET.parse(path).getroot()
    if root.tag != f"{{{SCHEMA}}}verification-metadata":
        raise AssertionError(f"Unexpected Gradle verification XML schema: {path}")
    allowed_root = {f"{{{SCHEMA}}}configuration", f"{{{SCHEMA}}}components"}
    if len(root) != 2 or {child.tag for child in root} != allowed_root:
        raise AssertionError(f"Unapproved Gradle trust metadata sections: {path}")
    config = root.find("g:configuration", NS)
    if config is None:
        raise AssertionError(f"Missing dependency verification configuration: {path}")
    allowed_configuration = {
        f"{{{SCHEMA}}}verify-metadata", f"{{{SCHEMA}}}verify-signatures",
    }
    if len(config) != 2 or {child.tag for child in config} != allowed_configuration:
        raise AssertionError(f"Unexpected Gradle verification trust configuration: {path}")
    if config.findtext("g:verify-metadata", namespaces=NS) != "true":
        raise AssertionError(f"Artifact POM/module metadata verification must be enabled: {path}")
    if config.findtext("g:verify-signatures", namespaces=NS) != "false":
        raise AssertionError(f"Use exact SHA-256 pins rather than signature-only trust: {path}")
    components = root.find("g:components", NS)
    if components is None or len(components) == 0:
        raise AssertionError(f"Empty dependency verification manifest: {path}")
    seen = set()
    count = 0
    for component in components:
        if component.tag != f"{{{SCHEMA}}}component":
            raise AssertionError(f"Unexpected Gradle component entry: {component.tag}")
        key = (component.get("group"), component.get("name"), component.get("version"))
        if not all(key) or key in seen:
            raise AssertionError(f"Malformed or duplicate component coordinates: {key}")
        seen.add(key)
        if len(component) == 0:
            raise AssertionError(f"Component without pinned artifacts: {key}")
        filenames = set()
        for artifact in component:
            if artifact.tag != f"{{{SCHEMA}}}artifact":
                raise AssertionError(f"Unreviewed component child: {artifact.tag}")
            name = artifact.get("name")
            if not name or name in filenames:
                raise AssertionError(f"Missing/duplicate artifact name in {key}")
            filenames.add(name)
            checksums = list(artifact)
            if len(checksums) != 1 or checksums[0].tag != f"{{{SCHEMA}}}sha256":
                raise AssertionError(f"Expected exactly one SHA-256 for {key}/{name}")
            check = checksums[0]
            if not SHA256.fullmatch(check.get("value", "")):
                raise AssertionError(f"Invalid SHA-256 checksum for {key}/{name}")
            count += 1
    return count


def manifest_pins(path: Path) -> dict[tuple[str, str, str, str], str]:
    pins = {}
    root = ET.parse(path).getroot()
    for component in root.findall("g:components/g:component", NS):
        coordinate = (
            component.attrib["group"],
            component.attrib["name"],
            component.attrib["version"],
        )
        for artifact in component.findall("g:artifact", NS):
            checksum = artifact.find("g:sha256", NS)
            if checksum is None:
                raise AssertionError(f"Artifact without SHA-256 in {path}")
            pins[(*coordinate, artifact.attrib["name"])] = checksum.attrib["value"]
    return pins


def audit_repository() -> None:
    counts = [audit_manifest(path) for path in MANIFESTS]
    # A root invocation includes build-logic. A standalone invocation may
    # resolve POMs that a warm root bootstrap skipped; keep the same SHA pins
    # in the outer trust manifest to ensure either build is protected.
    outer, included = (manifest_pins(path) for path in MANIFESTS)
    for key, checksum in included.items():
        if outer.get(key) != checksum:
            raise AssertionError(f"Root manifest is missing included-build pin {key}")
    for path, count in zip(MANIFESTS, counts):
        print(f"Verified {count} SHA-256-pinned artifacts in {path.relative_to(ROOT)}")


def copy_pinned_module_files(
    source: Path, destination: Path, allowed: set[str], *, staging: bool = False,
) -> int:
    """Transfer only pinned raw Gradle module files, never binary metadata."""
    if not source.is_dir() or source.is_symlink():
        raise AssertionError("Missing or unsafe module files directory")
    if destination.exists() or destination.is_symlink():
        raise AssertionError("Module files destination must be fresh")
    selected = []
    discarded = 0
    for directory, dirs, names in os.walk(source, followlinks=False):
        parent = Path(directory)
        for name in dirs:
            item = parent / name
            if not stat.S_ISDIR(item.lstat().st_mode):
                raise AssertionError(f"Unsafe module cache directory: {item}")
        for name in names:
            item = parent / name
            relative = item.relative_to(source)
            info = item.lstat()
            if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
                    or len(relative.parts) != 5
                    or not re.fullmatch(r"[0-9a-f]{40}", relative.parts[3])):
                raise AssertionError(f"Unsafe module cache entry: {relative}")
            with item.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            if digest not in allowed:
                if not staging:
                    raise AssertionError(f"Unpinned restored module bytes: {relative}")
                discarded += 1
                continue
            selected.append((item, relative))
    if not selected:
        raise AssertionError("No pinned module files to transfer")
    destination.mkdir(parents=True)
    for item, relative in selected:
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(item, target)
    print(f"Verified and copied {len(selected)} pinned module files; "
          f"discarded {discarded} unpinned files")
    return len(selected)


def make_module(repo: Path, module: str) -> tuple[Path, Path]:
    directory = repo / "org/example/fixture" / module / "1.0"
    directory.mkdir(parents=True)
    pom = directory / f"{module}-1.0.pom"
    pom.write_text(
        f'<project xmlns="http://maven.apache.org/POM/4.0.0">'
        f'<modelVersion>4.0.0</modelVersion>'
        f'<groupId>org.example.fixture</groupId><artifactId>{module}</artifactId>'
        f'<version>1.0</version></project>',
        encoding="utf-8",
    )
    jar = directory / f"{module}-1.0.jar"
    with ZipFile(jar, "w", ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n\n")
    return pom, jar


def checksum(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_fixture_manifest(project: Path, pom: Path, jar: Path) -> None:
    output = project / "gradle/verification-metadata.xml"
    output.parent.mkdir()
    output.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" '
        'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
        'xsi:schemaLocation="https://schema.gradle.org/dependency-verification '
        'https://schema.gradle.org/dependency-verification/dependency-verification-1.4.xsd">\n'
        '  <configuration><verify-metadata>true</verify-metadata>'
        '<verify-signatures>false</verify-signatures></configuration>\n'
        '  <components><component group="org.example.fixture" name="verified" version="1.0">\n'
        f'    <artifact name="{jar.name}"><sha256 value="{checksum(jar)}"/></artifact>\n'
        f'    <artifact name="{pom.name}"><sha256 value="{checksum(pom)}"/></artifact>\n'
        '  </component></components>\n'
        '</verification-metadata>\n',
        encoding="utf-8",
    )


def compile_fixture(project: Path, home: Path, *, offline: bool = False) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["GRADLE_USER_HOME"] = str(home)
    # No public network repository is configured in the fixture.
    cmd = [
        "gradle", "--no-daemon", "--console=plain", "--max-workers=1",
        "-Dorg.gradle.jvmargs=-Xmx384m", "--no-build-cache",
        "--no-configuration-cache", "--rerun-tasks",
        "--dependency-verification", "strict",
    ]
    if offline:
        cmd.append("--offline")
    cmd.append("compileJava")
    return subprocess.run(
        cmd, cwd=project, env=env, capture_output=True,
        text=True, timeout=180, check=False,
    )


def require_result(run: subprocess.CompletedProcess[str], *, success: bool, label: str) -> None:
    output = run.stdout + "\n" + run.stderr
    if success and run.returncode:
        raise AssertionError(f"{label} unexpectedly failed:\n{output[-5000:]}")
    if not success and (
        run.returncode == 0 or "dependency verification" not in output.lower()
    ):
        raise AssertionError(f"{label} was not rejected by dependency verification:\n{output[-5000:]}")
    print(f"PASS: {label}")


def negative_fixtures() -> None:
    if shutil.which("gradle") is None:
        raise RuntimeError("Gradle executable is mandatory for fixture tests")
    with tempfile.TemporaryDirectory(prefix="issue142-verification-") as temporary:
        tmp = Path(temporary)
        project = tmp / "fixture"
        project.mkdir()
        repository = project / "repository"
        pom, jar = make_module(repository, "verified")
        write_fixture_manifest(project, pom, jar)
        (project / "settings.gradle.kts").write_text('rootProject.name = "issue142-fixture"\n')
        source = project / "src/main/java/Fixture.java"
        source.parent.mkdir(parents=True)
        source.write_text("public class Fixture {}\n")

        # File-based Maven repositories can be used directly without storing
        # their artifacts in Gradle's module cache. An isolated loopback HTTP
        # repository exercises the actual checksum-verified downloaded cache.
        requests = []

        class QuietHandler(SimpleHTTPRequestHandler):
            def log_request(self, code: int | str = "-", size: int | str = "-") -> None:
                requests.append(self.command)

            def log_message(self, format: str, *args: object) -> None:
                pass

        server = ThreadingHTTPServer(
            ("127.0.0.1", 0),
            partial(QuietHandler, directory=str(repository)),
        )
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            port = server.server_address[1]
            build = project / "build.gradle.kts"
            build.write_text(
                'plugins { java }\n'
                'repositories { maven {\n'
                f'  url = uri("http://127.0.0.1:{port}/")\n'
                '  isAllowInsecureProtocol = true // localhost-only test fixture\n'
                '} }\n'
                'dependencies { implementation("org.example.fixture:verified:1.0") }\n'
            )
            home = tmp / "gradle-home"
            require_result(compile_fixture(project, home), success=True, label="pinned original")
            cold_requests = len(requests)
            module_files = Path("caches/modules-2/files-2.1")
            allowed = {checksum(pom), checksum(jar)}
            stage = tmp / "staged-module-files"
            restored_home = tmp / "restored-gradle-home"
            copy_pinned_module_files(home / module_files, stage, allowed, staging=True)
            copy_pinned_module_files(stage, restored_home / module_files, allowed)
            require_result(
                compile_fixture(project, restored_home),
                success=True, label="pinned original from restored module files",
            )
            print(f"Loopback fixture HTTP requests: cold={cold_requests}, "
                  f"restored={len(requests) - cold_requests}")
            restored_jar = next((restored_home / module_files).rglob(jar.name))
            restored_jar.write_bytes(restored_jar.read_bytes() + b"RESTORED-CACHE-MUTATION")
            require_result(
                compile_fixture(project, restored_home, offline=True),
                success=False, label="tampered restored cached JAR before compilation",
            )
            stage_jar = next(stage.rglob(jar.name))
            stage_jar.write_bytes(stage_jar.read_bytes() + b"UNPINNED-STAGED-MUTATION")
            try:
                copy_pinned_module_files(stage, tmp / "rejected-restore", allowed)
            except AssertionError as exc:
                if "Unpinned restored module bytes" not in str(exc):
                    raise
                print("PASS: corrupted restored archive rejected before Gradle")
            else:
                raise AssertionError("Corrupted restored module files were accepted")
            # Restore the fixture to valid bytes for the warm unapproved-artifact case.
            shutil.copyfile(jar, restored_jar)
            cached = list(
                (home / "caches/modules-2/files-2.1/org.example.fixture/verified/1.0")
                .rglob(jar.name)
            )
            if len(cached) != 1:
                raise AssertionError(f"Expected one cached verified JAR, found {len(cached)}")
            cached[0].write_bytes(cached[0].read_bytes() + b"\nUNREVIEWED-CACHE-MUTATION\n")
            require_result(
                compile_fixture(project, home, offline=True),
                success=False, label="tampered cached JAR before compilation",
            )
            # Change the dependency graph without modifying the pinned XML.
            make_module(repository, "unapproved")
            build.write_text(
                build.read_text() +
                'dependencies { implementation("org.example.fixture:unapproved:1.0") }\n'
            )
            require_result(
                compile_fixture(project, tmp / "second-gradle-home"),
                success=False, label="new dependency without a SHA-256 pin",
            )
            require_result(
                compile_fixture(project, restored_home),
                success=False, label="new dependency with restored cache and no SHA-256 pin",
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--negative-fixtures", action="store_true")
    group.add_argument("--stage-module-files", nargs=2, type=Path, metavar=("SOURCE", "DEST"))
    group.add_argument("--restore-module-files", nargs=2, type=Path, metavar=("SOURCE", "DEST"))
    options = parser.parse_args()
    audit_repository()
    if options.negative_fixtures:
        negative_fixtures()
    elif options.stage_module_files or options.restore_module_files:
        allowed = {digest for path in MANIFESTS for digest in manifest_pins(path).values()}
        copy_pinned_module_files(
            *(options.stage_module_files or options.restore_module_files), allowed,
            staging=options.stage_module_files is not None,
        )
