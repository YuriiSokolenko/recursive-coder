#!/usr/bin/env python3
"""Validate JUnit Jupiter tiers using compiled JVM class annotations and references.

No non-stdlib dependencies. Gradle compiles test classes before invoking this checker.
"""

import argparse
import os
from dataclasses import dataclass, field
from pathlib import Path
import struct
import sys

TEST_MARKERS = {
    "org/junit/jupiter/api/Test",
    "org/junit/jupiter/api/TestFactory",
    "org/junit/jupiter/api/TestTemplate",
    "org/junit/jupiter/api/RepeatedTest",
    "org/junit/jupiter/params/ParameterizedTest",
}
TIERS = {"unit", "integration", "e2e"}


class ClassFormatError(ValueError):
    pass


class Bytes:
    def __init__(self, data):
        self.data = memoryview(data)
        self.offset = 0

    def take(self, count):
        end = self.offset + count
        if end > len(self.data):
            raise ClassFormatError("Truncated JVM class file")
        part = self.data[self.offset:end]
        self.offset = end
        return part

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def u4(self):
        return struct.unpack(">I", self.take(4))[0]


@dataclass(frozen=True)
class Annotation:
    name: str
    values: dict


@dataclass
class Member:
    name: str
    descriptor: str
    annotations: list


@dataclass
class ClassInfo:
    name: str
    superclass: str | None
    interfaces: list
    flags: int
    annotations: list
    fields: list
    methods: list
    references: set
    source: str | None
    module: str
    class_path: Path
    method_references: set = field(default_factory=set)
    method_handle_references: set = field(default_factory=set)
    bootstrap_references: set = field(default_factory=set)


def parse_class(path, module):
    data = Bytes(path.read_bytes())
    if data.u4() != 0xCAFEBABE:
        raise ClassFormatError("Not a JVM class file")
    data.u2()  # minor
    data.u2()  # major
    cp = [None]
    count = data.u2()
    while len(cp) < count:
        kind = data.u1()
        if kind == 1:
            size = data.u2()
            cp.append((kind, bytes(data.take(size)).decode("utf-8", "replace")))
        elif kind in (3, 4):
            data.take(4)
            cp.append((kind,))
        elif kind in (5, 6):
            data.take(8)
            cp.extend(((kind,), None))
        elif kind in (7, 8, 16, 19, 20):
            cp.append((kind, data.u2()))
        elif kind in (9, 10, 11, 12, 17, 18):
            cp.append((kind, data.u2(), data.u2()))
        elif kind == 15:
            cp.append((kind, data.u1(), data.u2()))
        else:
            raise ClassFormatError(f"Unknown constant-pool tag {kind}")

    def utf(index):
        entry = cp[index]
        if entry is None or entry[0] != 1:
            raise ClassFormatError(f"Invalid UTF8 reference {index}")
        return entry[1]

    def class_name(index):
        return utf(cp[index][1]) if index else None

    def annotation(reader):
        descriptor = utf(reader.u2())
        if not (descriptor.startswith("L") and descriptor.endswith(";")):
            raise ClassFormatError(f"Invalid annotation descriptor: {descriptor}")
        name = descriptor[1:-1]
        values = {}
        for _ in range(reader.u2()):
            key = utf(reader.u2())
            values[key] = element(reader)
        return Annotation(name, values)

    def element(reader):
        kind = chr(reader.u1())
        if kind in "BCDFIJSZs":
            index = reader.u2()
            value = cp[index]
            return value[1] if value and value[0] == 1 else None
        if kind == "e":
            return (utf(reader.u2()), utf(reader.u2()))
        if kind == "c":
            return utf(reader.u2())
        if kind == "@":
            return annotation(reader)
        if kind == "[":
            return [element(reader) for _ in range(reader.u2())]
        raise ClassFormatError(f"Unknown annotation element type {kind}")

    bootstrap_indexes = []

    def attributes(reader):
        annotations = []
        source = None
        for _ in range(reader.u2()):
            name = utf(reader.u2())
            payload = Bytes(reader.take(reader.u4()))
            if name in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                annotations += [annotation(payload) for _ in range(payload.u2())]
            elif name in ("RuntimeVisibleParameterAnnotations",
                          "RuntimeInvisibleParameterAnnotations"):
                for _ in range(payload.u1()):
                    annotations += [annotation(payload) for _ in range(payload.u2())]
            elif name == "SourceFile":
                source = utf(payload.u2())
            elif name == "BootstrapMethods":
                for _ in range(payload.u2()):
                    bootstrap_indexes.append(payload.u2())
                    payload.take(payload.u2() * 2)
        return annotations, source

    flags = data.u2()
    name = class_name(data.u2())
    superclass = class_name(data.u2())
    interfaces = [class_name(data.u2()) for _ in range(data.u2())]
    fields = []
    for _ in range(data.u2()):
        data.u2()
        field_name = utf(data.u2())
        descriptor = utf(data.u2())
        annotations, _ = attributes(data)
        fields.append(Member(field_name, descriptor, annotations))
    methods = []
    for _ in range(data.u2()):
        data.u2()
        method_name = utf(data.u2())
        descriptor = utf(data.u2())
        annotations, _ = attributes(data)
        methods.append(Member(method_name, descriptor, annotations))
    annotations, source = attributes(data)
    references = {class_name(i) for i, item in enumerate(cp) if item and item[0] == 7}

    def member_ref(index):
        value = cp[index]
        if value is None or value[0] not in (9, 10, 11):
            raise ClassFormatError(f"Invalid constant-pool member ref {index}")
        name_and_type = cp[value[2]]
        if name_and_type is None or name_and_type[0] != 12:
            raise ClassFormatError("Invalid constant-pool NameAndType")
        return (class_name(value[1]), utf(name_and_type[1]), utf(name_and_type[2]))

    method_refs = {member_ref(index) for index, item in enumerate(cp)
                   if item and item[0] in (10, 11)}
    handle_refs = set()
    for item in cp:
        if item and item[0] == 15:
            index = item[2]
            if not 0 < index < len(cp):
                raise ClassFormatError(f"Invalid MethodHandle reference {index}")
            target = cp[index]
            if target is None or target[0] not in (9, 10, 11):
                raise ClassFormatError(f"Invalid MethodHandle target {index}")
            handle_refs.add(member_ref(index))
    bootstrap_refs = set()
    for index in bootstrap_indexes:
        handle = cp[index]
        if handle is None or handle[0] != 15:
            raise ClassFormatError(f"Invalid BootstrapMethods handle {index}")
        bootstrap_refs.add(member_ref(handle[2]))
    return ClassInfo(name, superclass, interfaces, flags, annotations, fields, methods,
                     references, source, module, path, method_refs, handle_refs, bootstrap_refs)


def expanded(annotations, classes, visited=frozenset()):
    """Follow locally compiled composed JUnit annotations, including nested tags."""
    for item in annotations:
        yield item
        if item.name not in visited and item.name in classes:
            yield from expanded(classes[item.name].annotations, classes,
                                visited | {item.name})


def tier_tags(annotations, classes):
    found = set()
    for item in expanded(annotations, classes):
        if item.name == "org/junit/jupiter/api/Tag":
            if isinstance(item.values.get("value"), str):
                found.add(item.values["value"])
        elif item.name == "org/junit/jupiter/api/Tags":
            for tag in item.values.get("value", []):
                if isinstance(tag, Annotation) and tag.name == "org/junit/jupiter/api/Tag":
                    found.add(tag.values.get("value"))
    return found & TIERS


def is_test(member, classes):
    return any(item.name in TEST_MARKERS for item in expanded(member.annotations, classes))


def ancestors(info, classes):
    """Superclasses and default-method interfaces, base types before derived types."""
    chain = []
    visited = set()

    def visit(current):
        if current is None or current.name in visited:
            return
        visited.add(current.name)
        visit(classes.get(current.superclass))
        for interface in current.interfaces:
            visit(classes.get(interface))
        chain.append(current)

    visit(info)
    return chain


def class_tiers(info, classes):
    tags = set()
    for item in ancestors(info, classes):
        tags |= tier_tags(item.annotations, classes)
    if "$" in info.name and any(
        a.name == "org/junit/jupiter/api/Nested" for a in expanded(info.annotations, classes)
    ):
        outer = classes.get(info.name.rsplit("$", 1)[0])
        if outer:
            tags |= class_tiers(outer, classes)
    return tags


def descendant_index(classes):
    """Index a class and its JVM '$' children once, including Kotlin-generated helpers."""
    index = {}
    for info in classes.values():
        pieces = info.name.split("$")
        for count in range(1, len(pieces) + 1):
            index.setdefault("$".join(pieces[:count]), []).append(info)
    return index


def pure_unit_related_classes(info, classes, descendants):
    """Include @Nested owners, inherited fixtures and Kotlin-generated JVM helpers.

    JUnit executes enclosing-class lifecycle fixtures for a nested test even when
    the outer class declares no @Test methods of its own.
    """
    owners = [info]
    current = info
    while "$" in current.name and any(
        a.name == "org/junit/jupiter/api/Nested" for a in expanded(current.annotations, classes)
    ):
        outer = classes.get(current.name.rsplit("$", 1)[0])
        if outer is None:
            break
        owners.append(outer)
        current = outer

    related_names = {parent.name for owner in owners for parent in ancestors(owner, classes)}
    related = {candidate.name: candidate for name in related_names
               for candidate in descendants.get(name, ())}
    return list(related.values())


def test_methods(info, classes):
    """Apply Java override rules; annotations on inherited methods are retained."""
    visible = {}
    for ancestor in ancestors(info, classes):
        for member in ancestor.methods:
            key = (member.name, member.descriptor.split(")")[0] + ")")
            if is_test(member, classes):
                visible[key] = member
            else:
                visible.pop(key, None)
    return visible.values()


def forbidden_reference(name):
    # A kotlin.io.CloseableKt.use call can manage an in-memory Closeable.
    # Only Kotlin file/path and console entry points imply external I/O here.
    prefixes = (
        "java/nio/file/", "java/nio/channels/", "java/net/http/",
        "javax/net/", "com/sun/net/httpserver/",
        "kotlin/io/FilesKt", "kotlin/io/path/", "kotlin/io/ConsoleKt",
        "kotlin/io/ReadWriteKt",
        "io/ktor/", "ai/koog/", "okhttp3/", "retrofit2/", "org/apache/http/",
        "org/eclipse/jetty/", "io/netty/", "org/eclipse/jgit/",
    )
    forbidden_classes = {
        "java/io/File", "java/io/FileInputStream", "java/io/FileOutputStream",
        "java/io/FileReader", "java/io/FileWriter", "java/io/RandomAccessFile",
        "java/lang/ProcessBuilder", "java/lang/Runtime", "java/util/zip/ZipFile",
        "java/net/URL", "java/net/URLConnection", "java/net/HttpURLConnection",
        "java/net/Socket", "java/net/ServerSocket", "java/net/DatagramSocket",
        "java/net/MulticastSocket", "java/net/InetSocketAddress",
        "java/net/SocketAddress", "java/net/DatagramPacket",
        "java/net/NetworkInterface", "java/net/InetAddress", "java/net/Proxy",
    }
    if name in forbidden_classes or any(name.startswith(p) for p in prefixes):
        return name.replace("/", ".")
    return None


def test_source_modules(root):
    """Discover test sources independently of Gradle's supplied class directories.

    The included build-logic build is verified separately; Gradle build output
    and caches must not be mistaken for newly registered JVM modules.
    """
    excluded = {"build", "build-logic", "node_modules", "__pycache__", "venv"}
    result = set()
    for parent, directories, _ in os.walk(root):
        directories[:] = [name for name in directories
                          if name not in excluded and not name.startswith(".")]
        path = Path(parent)
        if path.name == "test" and path.parent.name == "src":
            if any(source.suffix in {".java", ".kt"}
                   for source in path.rglob("*") if source.is_file()):
                result.add(path.parent.parent.relative_to(root).as_posix())
            directories.clear()
    return result


def report_location(info, root):
    if info.source:
        relative = Path(*info.name.split("/")[:-1]) / info.source
        for source_set in ("kotlin", "java"):
            candidate = root / info.module / "src/test" / source_set / relative
            if candidate.exists():
                return str(candidate.relative_to(root))
    return str(info.class_path.relative_to(root))


def validate(modules, root):
    problems = []
    checked = 0
    test_source_modules_cache = test_source_modules(root)
    for source_module in sorted(test_source_modules_cache):
        if source_module not in modules:
            problems.append(
                f":{source_module.replace('/', ':')}: test sources excluded from "
                "checkTestTiers discovery; include the Gradle module, apply "
                "the JVM convention plugin and pass its compiled test class directories"
            )
    for module, paths in sorted(modules.items()):
        classes = {}
        for directory in paths:
            if not directory.is_dir():
                continue
            for path in sorted(directory.rglob("*.class")):
                try:
                    info = parse_class(path, module)
                    if info.name in classes:
                        problems.append(f"{path}: duplicate JVM test class {info.name}")
                    classes[info.name] = info
                except (ValueError, IndexError, struct.error) as error:
                    problems.append(f"{path}: cannot inspect class: {error}")
        if module in test_source_modules_cache and not classes:
            problems.append(f":{module.replace('/', ':')}: no compiled test classes found; "
                            "check testClasses dependencies and --class-dir inputs")
        descendants = descendant_index(classes)
        purity_cache = {}
        for info in sorted(classes.values(), key=lambda c: c.name):
            if info.flags & 0x0600:  # interfaces and abstract bases aren't runnable by themselves
                continue
            methods = test_methods(info, classes)
            for method in methods:
                checked += 1
                tags = class_tiers(info, classes) | tier_tags(method.annotations, classes)
                location = report_location(info, root)
                target = f"{location}: {info.name.replace('/', '.')}#{method.name}{method.descriptor}"
                if not tags:
                    problems.append(f"{target}: missing JUnit tier; add @Tag(\"unit\"), "
                                    "@Tag(\"integration\") or @Tag(\"e2e\")")
                elif len(tags) > 1 and tags != {"integration", "e2e"}:
                    problems.append(f"{target}: incompatible JUnit tiers {sorted(tags)}; "
                                    "only integration + e2e is supported together")
                if tags == {"unit"}:
                    if info.name not in purity_cache:
                        related = pure_unit_related_classes(info, classes, descendants)
                        bad = {reference for related_class in related
                               for item in related_class.references
                               if (reference := forbidden_reference(item))}
                        if any(a.name == "org/junit/jupiter/api/io/TempDir"
                               for other in related for member in other.fields + other.methods
                               for a in expanded(member.annotations, classes)):
                            bad.add("org.junit.jupiter.api.io.TempDir")
                        purity_cache[info.name] = sorted(bad)
                    bad = purity_cache[info.name]
                    if bad:
                        problems.append(f"{target}: impure unit fixture references "
                                        f"{', '.join(sorted(set(bad)))}; use @Tag(\"integration\") "
                                        "or remove real I/O")
    if not checked:
        problems.append("No compiled JUnit Jupiter tests discovered; check testClasses task inputs")
    return checked, sorted(set(problems))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--class-dir", action="append", required=True,
                        help="MODULE=PATH; repeat for every Gradle test output directory")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    modules = {}
    for spec in args.class_dir:
        if "=" not in spec:
            parser.error("--class-dir must be MODULE=PATH")
        module, path = spec.split("=", 1)
        modules.setdefault(module.lstrip(":").replace(":", "/"), []).append(Path(path))
    checked, problems = validate(modules, root)
    if problems:
        print("Test tier enforcement failed:\n" + "\n".join(" - " + p for p in problems),
              file=sys.stderr)
        return 1
    print(f"Test tiers verified for {checked} compiled JUnit methods across {len(modules)} modules.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
