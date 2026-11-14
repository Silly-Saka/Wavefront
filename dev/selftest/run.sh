#!/usr/bin/env bash
# Compiles and runs the acoustics self-test without Minecraft.
# Needs a JDK (17+) and a Gson jar: pass its path as the first argument,
# or set GSON_JAR. Example: dev/selftest/run.sh ~/.m2/.../gson-2.10.jar
set -euo pipefail
cd "$(dirname "$0")/../.."
GSON="${1:-${GSON_JAR:-}}"
if [ -z "$GSON" ]; then echo "Pass the path to a Gson jar"; exit 2; fi
OUT="$(mktemp -d)"
javac -nowarn -d "$OUT" -cp "$GSON" src/main/java/wavefront/acoustics/*.java dev/selftest/SelfTest.java
java -cp "$OUT:$GSON" SelfTest src/main/resources/assets/wavefront/acoustics/acoustic_materials.json
