#!/usr/bin/env python3
"""Fail closed on reflective JVM bytecode and uncompiled production JAR classes.

Run after Gradle compiles and packages the production modules. Uses the shared
class-file parser from check_test_tiers.py, including constant-pool Methodrefs.
"""
import argparse
from collections import defaultdict
import hashlib
import os
from pathlib import Path
import re
import sys
from zipfile import BadZipFile, ZipFile

# -I strips script/cwd from sys.path; append only the trusted sibling parser
# after importing the stdlib, so untracked stdlib lookalikes cannot shadow it.
sys.path.append(str(Path(__file__).resolve().parent))
from check_test_tiers import ClassFormatError, parse_class

# Fail closed for unreviewed JDK packages and dynamic invocation services.
ALLOWED_JDK_PACKAGES = (
    "java/io/", "java/lang/", "java/math/", "java/net/", "java/nio/",
    "java/security/", "java/text/", "java/time/", "java/util/",
    "javax/crypto/", "javax/net/", "javax/security/",
)
DENIED_JDK_PREFIXES = (
    "java/beans/", "java/lang/instrument/", "java/lang/management/",
    "java/lang/foreign/", "java/util/logging/", "java/io/ObjectInputStream",
    "java/lang/invoke/", "java/lang/ClassLoader", "java/lang/ModuleLayer",
    "java/net/URLClassLoader", "java/security/SecureClassLoader",
    "java/security/AccessController", "java/util/ServiceLoader",
    "javax/script/", "javax/management/", "sun/", "jdk/", "com/sun/",
)
DYNAMIC_CLASSES = (
    "java/lang/reflect/", "kotlin/reflect/full/",
    "kotlin/reflect/jvm/internal/",
)
DYNAMIC_METHODS = {
    "java/lang/Class": {
        "forName", "getMethod", "getMethods", "getDeclaredMethod",
        "getDeclaredMethods", "getConstructor", "getConstructors",
        "getDeclaredConstructor", "getDeclaredConstructors",
        "getField", "getFields", "getDeclaredField", "getDeclaredFields",
        "getClassLoader", "newInstance",
    },
    "java/lang/Thread": {"getContextClassLoader", "setContextClassLoader"},
    "java/lang/System": {"load", "loadLibrary", "setSecurityManager"},
    "java/lang/Runtime": {"load", "loadLibrary"},
    # Git/sandbox adapters legitimately invoke ProcessBuilder.start; runtime
    # subprocess authorization is enforced outside this JVM reflection gate.
    "java/lang/Module": {"addOpens", "addExports", "addReads"},
}
# Invokevirtual/invokespecial owners may be user-defined subclasses.
DYNAMIC_METHOD_NAMES = {"defineClass", "loadClass", "findClass", "findSystemClass"}
ALLOWED_KOTLIN_REFLECT_METHODS = {
    ("kotlin/reflect/KClass", "getSimpleName"),
    ("kotlin/reflect/KClass", "getQualifiedName"),
    ("kotlin/reflect/KClass", "isInstance"),
    ("kotlin/reflect/KProperty", "getName"),
}
# Compiler bootstrap metadata is inert; invoking helpers directly is not.
COMPILER_BOOTSTRAP_TYPES = {
    "java/lang/invoke/MethodHandles",
    "java/lang/invoke/MethodHandles$Lookup",
    "java/lang/invoke/MethodHandle",
    "java/lang/invoke/LambdaMetafactory",
    "java/lang/invoke/StringConcatFactory",
    "java/lang/runtime/SwitchBootstraps",
}
ALLOWED_JVM_BOOTSTRAPS = {
    ("java/lang/invoke/LambdaMetafactory", "metafactory"),
    ("java/lang/invoke/LambdaMetafactory", "altMetafactory"),
    ("java/lang/invoke/StringConcatFactory", "makeConcat"),
    ("java/lang/invoke/StringConcatFactory", "makeConcatWithConstants"),
    ("java/lang/runtime/SwitchBootstraps", "typeSwitch"),
}


def forbidden_jdk_owner(owner: str) -> bool:
    if owner in COMPILER_BOOTSTRAP_TYPES:
        return False
    while owner.startswith("["):
        owner = owner[1:]
    if owner.startswith("L") and owner.endswith(";"):
        owner = owner[1:-1]
    if owner.startswith(DENIED_JDK_PREFIXES):
        return True
    if owner.startswith(("java/", "javax/", "sun/", "jdk/", "com/sun/")):
        return not owner.startswith(ALLOWED_JDK_PACKAGES)
    return False


# C3 authority is checked independently of the Kotlin vocabulary and source
# declarations. Names here are JVM identities, not production constants.
_C3_PACKAGE = "ai/yuriilab/recursivecoder/"
_C3_MARKER = _C3_PACKAGE + "TrustedRunningBoundaryApi"
_C3_RUNTIME = _C3_PACKAGE + "DefaultRunSessionUseCase"
_C3_PORT = _C3_PACKAGE + "TrustedSessionRunningHost"
_C3_HOST = _C3_PACKAGE + "DurableRuntimeAcceptedRunningHost"
_C3_HANDOFF = _C3_PACKAGE + "DurableAcceptedRunningHandoff"
_C3_LEDGER = _C3_PACKAGE + "DurableSessionEventLedger"
_C3_MARKED_CLASSES = {_C3_PORT, _C3_HOST, _C3_HANDOFF}
_C3_MODULES = {
    _C3_MARKER: "core/session", _C3_RUNTIME: "core/session",
    _C3_PORT: "core/session", _C3_HOST: "infra/journal",
    _C3_HANDOFF: "infra/journal", _C3_LEDGER: "infra/journal",
}
_C3_MARKED_METHODS = {
    _C3_RUNTIME: {"publishTrustedAcceptedRunning"},
    _C3_LEDGER: {"appendTrustedAcceptedRunning", "appendTrustedRunningWithFault"},
}
# No production caller of the host-only entry point or constructor is approved.
# A class-level allowlist is deliberately not a proof of behavior inside a body.
_C3_CALLERS = {
    (_C3_RUNTIME, "publishTrustedAcceptedRunning"): set(),
    (_C3_PORT, "publishAcceptedRunning"): {_C3_RUNTIME},
    (_C3_HOST, "publishAcceptedRunning"): set(),
    (_C3_HOST, "<init>"): set(),
    (_C3_HANDOFF, "publish"): {_C3_HOST},
    (_C3_LEDGER, "appendTrustedAcceptedRunning"): {_C3_HANDOFF},
    (_C3_LEDGER, "appendTrustedRunningWithFault"): {_C3_LEDGER},
}


def _c3_method_name(owner, name):
    # Kotlin internal functions carry a module suffix in JVM bytecode.
    # Treat every such spelling as the privileged entry, not a new exemption.
    if owner == _C3_LEDGER:
        return name.split("$", 1)[0]
    return name


def c3_boundary_violations(info):
    issues = []
    owner = info.name
    annotated = {a.name: a.values for a in info.annotations}
    location = _C3_MODULES.get(owner)
    if location and tuple(Path(info.module).parts[-2:]) != tuple(location.split("/")):
        issues.append(f"C3 class in unapproved module: {owner}")
    if (_C3_MARKER in annotated) != (owner in _C3_MARKED_CLASSES):
        issues.append(f"Unexpected C3 class marker: {owner}")
    marked_methods = [_c3_method_name(owner, m.name) for m in info.methods
                      if any(a.name == _C3_MARKER for a in m.annotations)]
    expected = _C3_MARKED_METHODS.get(owner, set())
    if set(marked_methods) != expected or len(marked_methods) != len(expected):
        issues.append(f"Unexpected C3 method markers: {owner}")
    if owner == _C3_MARKER:
        opt_in = annotated.get("kotlin/RequiresOptIn", {})
        retention = annotated.get("java/lang/annotation/Retention", {})
        target = annotated.get("java/lang/annotation/Target", {})
        if (opt_in.get("level") != ("Lkotlin/RequiresOptIn$Level;", "ERROR") or
                retention.get("value") != ("Ljava/lang/annotation/RetentionPolicy;", "CLASS") or
                set(target.get("value", [])) != {
                    ("Ljava/lang/annotation/ElementType;", "TYPE"),
                    ("Ljava/lang/annotation/ElementType;", "METHOD")}):
            issues.append("C3 marker must retain ERROR opt-in and CLASS retention/targets")
    # Forbid supertype laundering: only the exact final adapter implements the
    # port, and neither the port nor that adapter may gain another supertype.
    if owner == _C3_PORT and (info.interfaces or info.superclass != "java/lang/Object"):
        issues.append("C3 port has an unexpected supertype")
    if owner == _C3_HOST and (info.interfaces != [_C3_PORT] or
                              info.superclass != "java/lang/Object" or not info.flags & 0x0010):
        issues.append("C3 host must be final and implement only the marked port")
    if owner != _C3_HOST and _C3_PORT in info.interfaces:
        issues.append(f"Unapproved C3 port implementation: {owner}")
    for target, name, descriptor in sorted(info.method_references | info.method_handle_references):
        key = (target, _c3_method_name(target, name))
        if key in _C3_CALLERS and owner not in _C3_CALLERS[key]:
            issues.append(f"Unapproved C3 JVM call: {owner} -> {target}.{name}{descriptor}")
    return issues


def violations_for_class(path: Path, module: str) -> list[str]:
    """Inspect class, method and MethodHandle references fail-closed."""
    try:
        info = parse_class(path, module)
    except (ClassFormatError, ValueError, IndexError, TypeError) as error:
        return [f"{path}: invalid JVM class: {error}"]
    issues = c3_boundary_violations(info)
    for owner in sorted(info.references):
        if (forbidden_jdk_owner(owner) or
                (owner not in COMPILER_BOOTSTRAP_TYPES and
                 owner.startswith(DYNAMIC_CLASSES))):
            issues.append(f"{path}: forbidden JVM class {owner}")
    for owner, name, descriptor in sorted(
            info.method_references | info.method_handle_references):
        if ((owner, name) in ALLOWED_JVM_BOOTSTRAPS and
                (owner, name, descriptor) in info.bootstrap_references):
            continue
        forbidden_kotlin_reflect = (owner.startswith("kotlin/reflect/") and
                                    (owner, name) not in ALLOWED_KOTLIN_REFLECT_METHODS)
        if (name in DYNAMIC_METHOD_NAMES or
                name in DYNAMIC_METHODS.get(owner, set()) or
                forbidden_kotlin_reflect or
                forbidden_jdk_owner(owner) or
                owner.startswith(DYNAMIC_CLASSES) or
                owner.startswith("java/lang/invoke/")):
            issues.append(f"{path}: forbidden dynamic JVM method {owner}.{name}")
    for owner, name, _descriptor in sorted(info.bootstrap_references):
        if (owner, name) not in ALLOWED_JVM_BOOTSTRAPS:
            issues.append(f"{path}: unreviewed JVM invokedynamic bootstrap {owner}.{name}")
    return sorted(set(issues))


def product_modules(root: Path) -> list[Path]:
    settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    # This repository's include(...) declaration is the authoritative module set.
    names = sorted(set(re.findall(r'"(:[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*)"', settings)))
    if len(names) < 9:
        raise ValueError("Expected all nine reviewed production modules in settings.gradle.kts")
    return [root.joinpath(*name.lstrip(":").split(":")) for name in names]


def compiled_classes(module: Path):
    classes = {}
    problems = []
    for language in ("kotlin", "java"):
        directory = module / "build" / "classes" / language / "main"
        if not directory.is_dir():
            continue
        for source in sorted(directory.rglob("*.class")):
            relative = source.relative_to(directory).as_posix()
            if relative in classes:
                problems.append(f"{module}: duplicate compiled production class {relative}")
            else:
                classes[relative] = source
                problems.extend(violations_for_class(source, str(module)))
    if not classes:
        problems.append(f"{module}: production compilation produced no main JVM classes")
    return classes, problems


def validate_module_jar(module: Path, classes: dict[str, Path]):
    problems = []
    directory = module / "build" / "libs"
    jars = [p for p in sorted(directory.glob("*.jar"))
            if not p.name.endswith(("-sources.jar", "-javadoc.jar", "-test-fixtures.jar"))]
    if len(jars) != 1:
        return jars, [f"{module}: expected exactly one production JAR, got {len(jars)}"]
    jar = jars[0]
    try:
        with ZipFile(jar) as archive:
            manifests = [n for n in archive.namelist()
                         if n.upper() == "META-INF/MANIFEST.MF"]
            if len(manifests) > 1:
                problems.append(f"{jar}: duplicate JAR manifests")
            for manifest in manifests:
                data = archive.read(manifest).decode("utf-8", "replace")
                headers = re.findall(
                    r"(?m)^([A-Za-z0-9-]+):",
                    re.sub(r"\r\n |\r |\n ", "", data).replace("\r\n", "\n").replace("\r", "\n"))
                forbidden = {"class-path", "premain-class", "agent-class",
                             "launcher-agent-class", "boot-class-path"}
                for header in headers:
                    if header.lower() in forbidden:
                        problems.append(f"{jar}: forbidden manifest attribute {header}")
            members = [name for name in archive.namelist() if name.endswith(".class")]
            if len(members) != len(set(members)):
                problems.append(f"{jar}: duplicate class ZIP entries")
            packaged = set(members)
            for name in sorted(packaged - set(classes)):
                problems.append(f"{jar}: class {name} did not come from production compiler output")
            for name in sorted(set(classes) - packaged):
                problems.append(f"{jar}: compiled production class {name} missing from JAR")
            for name in sorted(packaged & set(classes)):
                if archive.read(name) != classes[name].read_bytes():
                    problems.append(f"{jar}: packaged {name} differs from compiler output")
            for name in archive.namelist():
                if name.lower().endswith(".jar"):
                    problems.append(f"{jar}: nested JAR entry is forbidden: {name}")
    except (BadZipFile, OSError, KeyError) as error:
        problems.append(f"{jar}: cannot inspect packaged classes: {error}")
    return jars, problems


def validate_launch_scripts(root: Path, jar_names: set[str],
                            trusted_launchers: Path | None = None) -> list[str]:
    """Verify static classpaths and exact Gradle launcher template bytes.

    The reference is generated in a separate, clean CI job from a minimal
    Gradle application project, NOT from the (untrusted) production build.
    Only the literal, fully validated CLASSPATH assignment is normalized
    to allow Gradle's dependency ordering to differ.
    """
    directory = root / "build" / "install" / "recursive-coder" / "bin"
    problems = []
    for name, windows in (("recursive-coder", False), ("recursive-coder.bat", True)):
        script = directory / name
        if not script.is_file() or script.is_symlink():
            problems.append(f"{script}: missing or non-regular installed launcher")
            continue
        content = script.read_bytes()
        try:
            decoded = content.decode("utf-8")
        except UnicodeDecodeError:
            problems.append(f"{script}: launcher is not UTF-8")
            continue
        # Require exactly one assignment with no whitespace/quote rewriting;
        # then compare every other byte with a trusted Gradle-generated template.
        pattern = (r"(?im)^set CLASSPATH=([^\r\n]*)\r?$" if windows
                   else r"(?m)^CLASSPATH=([^\r\n]*)\r?$")
        matches = list(re.finditer(pattern, decoded))
        # Unix scripts legitimately convert CLASSPATH with cygpath at runtime.
        if not windows:
            matches = [match for match in matches if
                       match.group(0) !=
                       'CLASSPATH=$( cygpath --path --mixed "$CLASSPATH" )']
        if len(matches) != 1:
            problems.append(f"{script}: expected one static CLASSPATH")
            continue
        items = matches[0].group(1).strip('"').split(";" if windows else ":")
        prefix = "%APP_HOME%\\lib\\" if windows else "$APP_HOME/lib/"
        resolved = [item[len(prefix):] for item in items
                    if item.startswith(prefix)]
        if (len(resolved) != len(items) or len(resolved) != len(jar_names)
                or len(set(resolved)) != len(resolved) or set(resolved) != jar_names):
            problems.append(f"{script}: unreviewed launcher CLASSPATH")
            continue
        if trusted_launchers is None:
            # Unit tests can exercise the classpath portion alone, but the
            # production CLI requires the independent template comparison.
            continue
        expected = trusted_launchers / name
        if not expected.is_file() or expected.is_symlink():
            problems.append(f"{expected}: missing trusted launcher template")
            continue
        try:
            reference = expected.read_bytes().decode("utf-8")
        except UnicodeDecodeError:
            problems.append(f"{expected}: invalid trusted launcher encoding")
            continue
        def normalize(value: str) -> str:
            # Only the one reviewed literal assignment is substituted.
            return re.sub(pattern, lambda m:
                          m.group(0).split("=", 1)[0] + "=<reviewed-classpath>",
                          value, count=1)
        # Cygwin's second assignment is never the first one in Gradle output.
        if normalize(decoded) != normalize(reference):
            problems.append(f"{script}: installed launcher differs from trusted Gradle template")
    return problems


def validate_install_distribution(root: Path, project_jars: list[Path], home: Path,
                                  trusted_launchers: Path | None = None,
                                  runtime_manifest: Path | None = None):
    library = root / "build" / "install" / "recursive-coder" / "lib"
    if not library.is_dir():
        return [f"{library}: installed CLI runtime libraries are missing"]
    # Reject extras, symlinks and nested payloads throughout the install tree.
    install_root = library.parent
    unexpected = []
    for item in sorted(install_root.rglob("*")):
        relative = item.relative_to(install_root).as_posix()
        allowed = (relative in ("bin", "lib") and item.is_dir()
                   or relative in ("bin/recursive-coder", "bin/recursive-coder.bat")
                   and item.is_file()
                   or item.parent == library and item.suffix == ".jar" and item.is_file())
        if item.is_symlink() or not allowed:
            unexpected.append(f"{item}: unreviewed installed distribution entry")
    installed = sorted(library.glob("*.jar"))
    if not installed:
        return [f"{library}: installed CLI has no runtime libraries"]
    names = [jar.name for jar in project_jars]
    if len(names) != len(set(names)):
        return ["Duplicate production module JAR filenames"]
    expected = {jar.name: hashlib.sha256(jar.read_bytes()).hexdigest()
                for jar in project_jars}
    # A copied external library must be byte-identical to an existing Gradle
    # dependency-cache artifact, not an arbitrary injected distribution file.
    cache = home / "caches" / "modules-2" / "files-2.1"
    by_name = defaultdict(set)
    if runtime_manifest is None and cache.is_dir():
        for jar in cache.rglob("*.jar"):
            by_name[jar.name].add(jar)
    problems = unexpected
    resolved_runtime = {}
    if runtime_manifest is not None:
        if not runtime_manifest.is_file() or runtime_manifest.is_symlink():
            problems.append(f"{runtime_manifest}: missing resolved runtimeClasspath manifest")
        else:
            for record in runtime_manifest.read_text(encoding="utf-8").splitlines():
                pieces = record.split("\t")
                if (len(pieces) != 2 or
                        not re.fullmatch(r"[A-Za-z0-9_.+-]+\.jar", pieces[0]) or
                        not re.fullmatch(r"[0-9a-f]{64}", pieces[1]) or
                        pieces[0] in resolved_runtime):
                    problems.append(f"{runtime_manifest}: invalid or duplicate runtimeClasspath entry")
                    continue
                resolved_runtime[pieces[0]] = pieces[1]
        if not resolved_runtime:
            problems.append(f"{runtime_manifest}: empty resolved runtimeClasspath manifest")
    for file in installed:
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        if file.name in expected:
            if digest != expected[file.name]:
                problems.append(f"{file}: internal JAR differs from verified build output")
        else:
            if runtime_manifest is not None:
                # In the independent verifier job the build job's Gradle cache
                # does not exist. Use its resolved runtimeClasspath receipt as
                # the allowed *set*; #142 will pin external artifact provenance.
                if resolved_runtime.get(file.name) != digest:
                    problems.append(f"{file}: absent from resolved application runtimeClasspath")
            elif not any(hashlib.sha256(jar.read_bytes()).hexdigest() == digest
                         for jar in by_name.get(file.name, ())):
                problems.append(f"{file}: unreviewed external distribution JAR")
    missing = set(expected) - {jar.name for jar in installed}
    for name in sorted(missing):
        problems.append(f"{library}: missing production JAR {name}")
    problems.extend(validate_launch_scripts(root, {jar.name for jar in installed},
                                            trusted_launchers))
    return problems


def validate(root: Path, gradle_home: Path,
             trusted_launchers: Path | None = None,
             runtime_manifest: Path | None = None):
    problems = []
    product_jars = []
    modules = product_modules(root)
    for module in modules:
        classes, errors = compiled_classes(module)
        problems.extend(errors)
        jars, errors = validate_module_jar(module, classes)
        problems.extend(errors)
        product_jars.extend(jars)
    if not problems:
        problems.extend(validate_install_distribution(root, product_jars, gradle_home,
                                                      trusted_launchers, runtime_manifest))
    return sorted(set(problems))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--gradle-user-home", type=Path,
                        default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    parser.add_argument("--trusted-launchers", type=Path,
                        help="Independent Gradle 9.8.2 startScripts output; required in CI")
    parser.add_argument("--runtime-classpath-manifest", type=Path,
                        help="Gradle-resolved CLI runtime JAR SHA-256 list; required in CI")
    args = parser.parse_args(argv)
    if not args.trusted_launchers or not args.runtime_classpath_manifest:
        parser.error("--trusted-launchers and --runtime-classpath-manifest are required")
    try:
        problems = validate(args.root.resolve(), args.gradle_user_home.resolve(),
                            args.trusted_launchers.resolve(),
                            args.runtime_classpath_manifest.resolve())
    except (OSError, ValueError) as error:
        problems = [f"Production bytecode inputs unavailable: {error}"]
    if problems:
        print("Production JVM bytecode boundary violated:\n" +
              "\n".join(" - " + p for p in problems), file=sys.stderr)
        return 1
    print("Verified production JVM class references, JAR byte equality and distribution.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
