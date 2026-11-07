#!/usr/bin/env bash
set -euo pipefail
# No environment dump, command tracing or secrets in arguments. Android device runs are separate.
cd "$(dirname "$0")/.."
mkdir -p .local/evidence .local/java-fixtures
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --rerun-tasks \
  > .local/evidence/gradle-validation.txt 2>&1
./gradlew :app:dependencies --configuration releaseRuntimeClasspath > .local/evidence/dependency-inventory.txt 2>&1
./gradlew :app:writeOwnedDependencyComponents --init-script tools/dependency-components.gradle \
  > .local/evidence/dependency-components-task.txt 2>&1
python3 -m unittest discover -s tools -p 'test_query_public_dependency_advisories.py' \
  > .local/evidence/python-sca-tool-tests.txt 2>&1
# Separate explicit public-coordinate lookup:
# python3 tools/query_public_dependency_advisories.py .local/evidence/release-components.json .local/evidence/osv-release.json
javac --add-modules jdk.httpserver -d .local/java-fixtures \
  tools/LocalTlsPolicy.java tools/LocalLab15MockServer.java tools/LocalLab15MockServerTest.java \
  tools/LocalLab15WssServer.java tools/LocalLab15WssServerTest.java tools/LocalLab15TlsDowngradeTest.java
java --add-modules jdk.httpserver -cp .local/java-fixtures LocalLab15MockServerTest > .local/evidence/java-http.txt
java --add-modules jdk.httpserver -cp .local/java-fixtures LocalLab15WssServerTest > .local/evidence/java-wss.txt
printf '%s\n' 'Requested local checks completed. Read their outputs; this is not an all-labs/device certification.'
