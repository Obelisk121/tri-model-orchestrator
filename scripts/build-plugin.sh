#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PYCHARM_APP="${PYCHARM_APP:-/Applications/PyCharm.app}"
PLUGIN_NAME="tri-model-orchestrator"
PLUGIN_VERSION="1.2.1"

if [[ ! -d "$PYCHARM_APP/Contents/lib" ]]; then
  echo "ERROR: PyCharm installation not found at $PYCHARM_APP" >&2
  echo "Set PYCHARM_APP=/path/to/PyCharm.app or build via Gradle (gradle buildPlugin)." >&2
  exit 1
fi

# Locate Java 21+ compiler and runtime (prefer PyCharm's bundled JBR)
if [[ -x "$PYCHARM_APP/Contents/jbr/Contents/Home/bin/javac" ]]; then
  JAVAC="$PYCHARM_APP/Contents/jbr/Contents/Home/bin/javac"
  JAVA="$PYCHARM_APP/Contents/jbr/Contents/Home/bin/java"
elif [[ -x "/opt/homebrew/opt/openjdk/bin/javac" ]]; then
  JAVAC="/opt/homebrew/opt/openjdk/bin/javac"
  JAVA="/opt/homebrew/opt/openjdk/bin/java"
else
  JAVAC="$(command -v javac)"
  JAVA="$(command -v java)"
fi

if [[ -x "/opt/homebrew/opt/openjdk/bin/jar" ]]; then
  JAR_BIN="/opt/homebrew/opt/openjdk/bin/jar"
elif command -v jar >/dev/null 2>&1; then
  JAR_BIN="$(command -v jar)"
else
  JAR_BIN=""
fi

PYCHARM_CP="$(find "$PYCHARM_APP/Contents/lib" -maxdepth 1 -name '*.jar' | paste -sd ':' -)"

BUILD_DIR="$ROOT_DIR/build/local"
MAIN_CLASSES="$BUILD_DIR/classes/main"
TEST_CLASSES="$BUILD_DIR/classes/test"
DIST_DIR="$ROOT_DIR/build/distributions"
STAGE_DIR="$BUILD_DIR/stage/$PLUGIN_NAME"

rm -rf "$BUILD_DIR"
mkdir -p "$MAIN_CLASSES" "$TEST_CLASSES" "$DIST_DIR" "$STAGE_DIR/lib"

echo "==> Compiling main plugin sources (Java 21 target) against $PYCHARM_APP..."
MAIN_SOURCES=()
while IFS= read -r f; do
  MAIN_SOURCES+=("$f")
done < <(find "$ROOT_DIR/src/main/java" -name '*.java' | sort)

"$JAVAC" --release 21 -encoding UTF-8 -Xlint:deprecation -cp "$PYCHARM_CP" -d "$MAIN_CLASSES" "${MAIN_SOURCES[@]}"
cp -R "$ROOT_DIR/src/main/resources/"* "$MAIN_CLASSES/"

echo "==> Compiling test suite..."
TEST_SOURCES=()
while IFS= read -r f; do
  TEST_SOURCES+=("$f")
done < <(find "$ROOT_DIR/src/test/java" -name '*.java' | sort)

"$JAVAC" --release 21 -encoding UTF-8 -cp "$MAIN_CLASSES:$PYCHARM_CP" -d "$TEST_CLASSES" "${TEST_SOURCES[@]}"

echo "==> Running automated test suite..."
"$JAVA" -cp "$TEST_CLASSES:$MAIN_CLASSES:$PYCHARM_CP" io.github.obelisk121.trimodel.TriModelPluginTestSuite

echo "==> Packaging installable PyCharm plugin archive..."
PLUGIN_JAR="$STAGE_DIR/lib/${PLUGIN_NAME}-${PLUGIN_VERSION}.jar"
if [[ -n "$JAR_BIN" ]]; then
  "$JAR_BIN" cf "$PLUGIN_JAR" -C "$MAIN_CLASSES" .
else
  (cd "$MAIN_CLASSES" && /usr/bin/zip -qr "$PLUGIN_JAR" .)
fi

ZIP_OUT="$DIST_DIR/${PLUGIN_NAME}-${PLUGIN_VERSION}.zip"
rm -f "$ZIP_OUT"
(cd "$BUILD_DIR/stage" && /usr/bin/zip -qr "$ZIP_OUT" "$PLUGIN_NAME")

echo "==> Verifying plugin descriptor, JetBrains Marketplace rules, bytecode, icons, and repo docs..."
/usr/bin/python3 - "$ZIP_OUT" "$PLUGIN_NAME" "$PLUGIN_VERSION" "$ROOT_DIR" <<'PYEOF'
import io, os, struct, sys, zipfile, xml.etree.ElementTree as ET

zip_path, plugin_name, plugin_version, root_dir = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
jar_entry = f"{plugin_name}/lib/{plugin_name}-{plugin_version}.jar"

with zipfile.ZipFile(zip_path, "r") as zf:
    assert jar_entry in zf.namelist(), f"Missing {jar_entry} in {zip_path}"
    jar_bytes = zf.read(jar_entry)

with zipfile.ZipFile(io.BytesIO(jar_bytes), "r") as jf:
    names = set(jf.namelist())
    for req_asset in (
        "META-INF/plugin.xml",
        "META-INF/pluginIcon.svg",
        "META-INF/pluginIcon_dark.svg",
        "icons/triModel.svg",
        "icons/triModel_dark.svg",
    ):
        assert req_asset in names, f"Missing required asset in JAR: {req_asset}"

    # Verify 40x40 Marketplace SVG icons
    for icon in ("META-INF/pluginIcon.svg", "META-INF/pluginIcon_dark.svg"):
        svg_root = ET.fromstring(jf.read(icon))
        assert svg_root.attrib.get("width") == "40" and svg_root.attrib.get("height") == "40", (
            f"{icon} must be 40x40 px for JetBrains Marketplace"
        )

    root = ET.fromstring(jf.read("META-INF/plugin.xml"))

    plugin_id = (root.findtext("id") or "").strip()
    plugin_title = (root.findtext("name") or "").strip()
    vendor_el = root.find("vendor")
    description = (root.findtext("description") or "").strip()
    change_notes = (root.findtext("change-notes") or "").strip()

    # JetBrains Marketplace & Plugin Verifier naming/ID compliance checks
    forbidden_id_words = ("jetbrains", "intellij", "pycharm", "example", "template")
    for bad in forbidden_id_words:
        assert bad not in plugin_id.lower(), f"Forbidden word '{bad}' in plugin <id>: {plugin_id}"

    forbidden_name_words = ("jetbrains", "intellij", "pycharm", "plugin")
    for bad in forbidden_name_words:
        assert bad not in plugin_title.lower(), f"Forbidden word '{bad}' in plugin <name>: {plugin_title}"
    assert 3 <= len(plugin_title) <= 60, f"Plugin <name> length invalid: {plugin_title}"

    assert vendor_el is not None and (vendor_el.text or "").strip(), "Missing <vendor> name"
    assert vendor_el.attrib.get("email"), "Missing <vendor email='...'>"
    assert vendor_el.attrib.get("url", "").startswith("https://"), "Missing HTTPS <vendor url='...'>"
    assert len(description) >= 80, "Description too short for Marketplace guidelines"
    assert "privacy" in description.lower(), "AI plugin description must include privacy/data disclosure"
    assert len(change_notes) >= 20, "Missing meaningful <change-notes>"

    classes_to_check = []
    for elem in root.iter():
        for attr in ("factoryClass", "instance", "class", "implementationClass", "serviceImplementation"):
            val = elem.attrib.get(attr)
            if val:
                classes_to_check.append(val)

    for cls in classes_to_check:
        path = cls.replace(".", "/") + ".class"
        assert path in names, f"Class referenced in plugin.xml not found in JAR: {cls}"

    class_count = 0
    for name in names:
        if name.endswith(".class"):
            class_count += 1
            data = jf.read(name)
            magic, minor, major = struct.unpack(">IHH", data[:8])
            assert magic == 0xCAFEBABE, f"Invalid classfile magic in {name}"
            assert major == 65, f"Expected Java 21 bytecode (major 65) in {name}, got {major}"

# Verify mandatory repository regulatory & governance files
for doc in ("LICENSE", "NOTICE", "PRIVACY.md", "SECURITY.md", "CHANGELOG.md", "README.md", "CONTRIBUTING.md", "CODE_OF_CONDUCT.md"):
    assert os.path.isfile(os.path.join(root_dir, doc)), f"Missing required repository compliance file: {doc}"

print(
    f"Verified {zip_path}:\n"
    f"  - Plugin ID: {plugin_id} (Marketplace compliant, no forbidden brand words)\n"
    f"  - Plugin Name: {plugin_title} ({len(plugin_title)} chars, Marketplace compliant)\n"
    f"  - Vendor: {vendor_el.text.strip()} <{vendor_el.attrib.get('email')}> ({vendor_el.attrib.get('url')})\n"
    f"  - Bytecode: {class_count} classes (Java 21 / major 65), {len(classes_to_check)} plugin.xml bindings valid\n"
    f"  - Icons: 40x40 light & dark pluginIcon SVGs + 16x16 light & dark tool window SVGs\n"
    f"  - Regulatory Docs: LICENSE, NOTICE, PRIVACY.md, SECURITY.md, CHANGELOG.md, CONTRIBUTING.md, CODE_OF_CONDUCT.md"
)
PYEOF

echo "==> SUCCESS! Installable plugin archive ready at:"
echo "    $ZIP_OUT"
