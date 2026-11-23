"""Verify actual Kotlin-generated class files without executing negative fixtures."""
import sys
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
    if not required <= names or not any(
        name.startswith("example/LambdaFixture$") for name in names
    ):
        raise SystemExit(f"Missing Kotlin-generated helper classes: {sorted(names)}")
    # Isolate the fixture directory from the real repository source inventory.
    count, problems = guard.validate({"fixture": [classes]}, classes.parent)
    expected = {"CompanionFixture#companion", "LambdaFixture#lambda",
                "DefaultFixture#inheritedDefault"}
    if count != 4 or len(problems) != len(expected) or any(
        not any(target in problem and "java.net.Socket" in problem
                for problem in problems) for target in expected
    ):
        raise SystemExit(f"Unexpected Kotlin guard results: {count}, {problems}")
    print("Verified real Kotlin lambda, companion, DefaultImpls and pure use fixtures")


if __name__ == "__main__":
    main()
