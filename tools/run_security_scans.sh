#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .local/evidence
for scanner in semgrep gitleaks; do command -v "$scanner" >/dev/null || { printf '%s\n' 'Required scanner is not installed; no clean-scan claim is made.' >&2; exit 2; }; done
semgrep --version > .local/evidence/semgrep-version.txt
gitleaks version > .local/evidence/gitleaks-version.txt
semgrep scan --metrics=off --disable-version-check --validate --config tools/semgrep-security.yml
semgrep scan --metrics=off --disable-version-check --config tools/semgrep-security.yml --json --output .local/evidence/semgrep.json app/src/main/java
python3 tools/package_submission.py .local/evidence/source-for-scanners.zip
scan_source=$(mktemp -d .local/scanner-source.XXXXXX)
trap 'rm -rf -- "$scan_source"' EXIT
python3 -m zipfile -e .local/evidence/source-for-scanners.zip "$scan_source"
gitleaks dir "$scan_source" --redact --report-format json --report-path .local/evidence/gitleaks-source.json
if [[ ${1:-} == history ]]; then
    git rev-parse --git-dir >/dev/null
    gitleaks git . --redact --report-format json --report-path .local/evidence/gitleaks-history.json
fi
printf '%s\n' 'Requested scanners completed; inspect findings. This does not perform CVE/SCA analysis.'
