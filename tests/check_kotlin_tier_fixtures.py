"""Verify actual Kotlin-generated class files without executing negative fixtures."""
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import check_test_tiers as guard


def main():
    classes = Path(sys.argv[1]).resolve()
    names = {path.relative_to(classes).as_posix()
             for path in classes.rglob("*.class")}
    required = {"example/CompanionFixture$Companion.class",
                "example/DefaultFixtureContract$DefaultImpls.class"}
    if not required <= names:
        raise SystemExit(f"Missing Kotlin-generated helper classes: {sorted(names)}")
    # An empty inventory root isolates fixtures from actual repository sources.
    # Copy only class files so diagnostic paths remain relative to that root.
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        copied = root / "classes"
        shutil.copytree(classes, copied)
        lambda_class = guard.parse_class(copied / "example/LambdaFixture.class", "fixture")
        if not any(owner == "java/lang/invoke/LambdaMetafactory"
                   for owner, _, _ in lambda_class.bootstrap_references):
            raise SystemExit("Expected default Kotlin indy lambda codegen")
        count, problems = guard.validate({"fixture": [copied]}, root)
    expected = {"CompanionFixture#companion", "LambdaFixture#lambda",
                "DefaultFixture#inheritedDefault"}
    if count != 5 or len(problems) != len(expected) + 1 or not any(
        "NetworkFixture#readUrl" in problem and "java.net.URL" in problem
        and "kotlin.io.TextStreamsKt" in problem for problem in problems
    ) or any(
        not any(target in problem and "java.net.Socket" in problem
                for problem in problems) for target in expected
    ):
        raise SystemExit(f"Unexpected Kotlin guard results: {count}, {problems}")
    print("Verified real Kotlin lambda, companion, DefaultImpls and pure use fixtures")


if __name__ == "__main__":
    main()
