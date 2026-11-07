#!/usr/bin/env bash
set -euo pipefail
# Explicit owner device only. Never stores PINs, key values, frames or recovery data.
cd "$(dirname "$0")/.."
: "${ANDROID_SERIAL:?Select your own device locally before running}"
mode=${1:?Choose core, ui, invite, auth-bound or tls}
mkdir -p .local/evidence
adb -s "$ANDROID_SERIAL" get-state >/dev/null
adb -s "$ANDROID_SERIAL" shell input keyevent KEYCODE_WAKEUP
power_state=$(adb -s "$ANDROID_SERIAL" shell dumpsys power)
if ! grep -q 'mWakefulness=Awake' <<<"$power_state"; then
    printf '%s\n' 'Screen is not awake. Unlock your own device manually; no auth bypass is attempted.' >&2; exit 2
fi
case "$mode" in
 core) classes=com.phonoteka.security.RefreshTokenStoreTest,com.phonoteka.data.LibraryBackupFileStoreTest,com.phonoteka.data.notes.AndroidNoteStorageTest,com.phonoteka.security.AccessBoundaryTest,com.phonoteka.data.LocalRoomCatalogContractTest ;;
 ui) classes=com.phonoteka.ui.Lab8AccessibilityTest,com.phonoteka.ui.Lab9PhonotekaUiTest,com.phonoteka.ui.TrackNotesDurabilityUiTest ;;
 invite) classes=com.phonoteka.security.InviteManifestContractTest,com.phonoteka.data.ModeledVersion2MigrationTest ;;
 auth-bound) classes=com.phonoteka.security.AuthBoundRefreshPolicyTest ;;
 tls) classes=com.phonoteka.security.LocalLab15TlsClientTest
      reverse_state=$(adb -s "$ANDROID_SERIAL" reverse --list)
      if grep -Eq 'tcp:8443([[:space:]]|$)' <<<"$reverse_state"; then
          printf '%s\n' '8443 reverse is already owned by another run; will not overwrite it.' >&2; exit 2
      fi
      adb -s "$ANDROID_SERIAL" reverse tcp:8443 tcp:8443
      trap 'adb -s "$ANDROID_SERIAL" reverse --remove tcp:8443 >/dev/null 2>&1 || true' EXIT ;;
 *) printf '%s\n' 'Unknown mode' >&2; exit 2 ;;
esac
command -v timeout >/dev/null || { printf '%s\n' 'GNU timeout is required to bound a hung runner' >&2; exit 2; }
# Fixture absence, hung UI, a skipped key policy precondition are NOT successful acceptance.
timeout --foreground 300s ./gradlew :app:connectedDebugAndroidTest   "-Pandroid.testInstrumentationRunnerArguments.class=$classes" --rerun-tasks   > ".local/evidence/device-$mode.txt" 2>&1
printf '%s\n' 'Runner finished. Inspect XML for failed/skipped tests; this message is not a pass certificate.'
