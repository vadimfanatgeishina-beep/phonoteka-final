#!/usr/bin/env python3
"""Allowlisted source archive. Never traverses work/, .git/, caches or credentials."""
from pathlib import Path
import sys, zipfile
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / 'Phonoteka-source-candidate.zip'
DIRECTORIES = ('app', 'tools', 'gradle', 'benchmark', 'lab_reports')
EXACT = ('gradlew', 'gradlew.bat', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', '.gitignore', '.gitlab-ci.yml')
ALLOWED = {'.kt', '.kts', '.gradle', '.java', '.xml', '.json', '.csv', '.md', '.txt', '.properties', '.pro', '.bat', '.sh', '.py', '.yml', '.yaml', '.png', '.cer', '.jar'}
FORBIDDEN_PARTS = {'.git', '.gradle', 'build', 'work', 'node_modules', '.idea', '.local'}
FORBIDDEN_SUFFIXES = {'.key', '.pem', '.p12', '.pfx', '.jks', '.keystore', '.env'}
files = [ROOT / name for name in EXACT if (ROOT / name).is_file()] + sorted(ROOT.glob('*.md'))
for name in DIRECTORIES:
    directory = ROOT / name
    if not directory.exists(): continue
    for file in directory.rglob('*'):
        relative = file.relative_to(ROOT)
        if any(part in FORBIDDEN_PARTS for part in relative.parts): continue
        if file.is_symlink(): raise SystemExit('Symlink is not allowed in source submission')
        if not file.is_file(): continue
        if file.suffix.lower() in FORBIDDEN_SUFFIXES or file.name.startswith('deploy_ed25519') or file.name in {'local.properties', 'secrets.properties', 'google-services.json'}:
            raise SystemExit('A forbidden credential artifact is present in an allowlisted directory')
        if file.suffix.lower() not in ALLOWED: continue
        files.append(file)
for file in files:
    first = file.read_bytes()[:150].lstrip()
    if first.startswith(b'-----BEGIN ') and b'PRIVATE KEY' in first:
        raise SystemExit('Private key container detected; archive not written')
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(OUTPUT, 'w', zipfile.ZIP_DEFLATED) as archive:
    for file in sorted(set(files)):
        archive.write(file, file.relative_to(ROOT).as_posix())
print('Source submission written; excluded work, credentials, build outputs and Git history.')
