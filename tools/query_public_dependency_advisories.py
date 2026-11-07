#!/usr/bin/env python3
"""OSV version lookup: sends only public Maven coordinates, never project source.
A zero-match result is not proof of absence of vulnerabilities or shaded code.
"""
import argparse
import datetime
import json
from pathlib import Path
import urllib.request


def coordinates(components):
    result = {}
    for item in components:
        if not isinstance(item, dict):
            raise ValueError('Invalid component record')
        triple = tuple(item.get(key) for key in ('group', 'name', 'version'))
        if not all(isinstance(x, str) and x and not any(c.isspace() for c in x) for x in triple):
            raise ValueError('Expected resolved public group/name/version')
        group, name, version = triple
        if (group, name) in result and result[(group, name)] != version:
            raise ValueError('Conflicting resolved versions')
        result[(group, name)] = version
    if not result:
        raise ValueError('Empty dependency inventory is not a successful scan')
    return [{'ecosystem': 'Maven', 'name': g + ':' + n, 'version': v}
            for (g, n), v in sorted(result.items())]


def query(packages):
    queries = [{'package': {'ecosystem': p['ecosystem'], 'name': p['name']},
                'version': p['version']} for p in packages]
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
        data=json.dumps({'queries': queries}).encode(),
        headers={'Content-Type': 'application/json', 'User-Agent': 'Phonoteka-owned-coordinate-check'})
    with urllib.request.urlopen(request, timeout=90) as response:
        results = json.load(response).get('results')
    if not isinstance(results, list) or len(results) != len(packages):
        raise ValueError('Incomplete OSV response: no successful scan claim')
    findings = []
    for package, result in zip(packages, results):
        if not isinstance(result, dict) or result.get('error'):
            raise ValueError('OSV returned an error for a component')
        for vulnerability in result.get('vulns', []):
            findings.append({'package': package, 'id': vulnerability['id']})
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('components', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    packages = coordinates(json.loads(args.components.read_text()))
    findings = query(packages)
    report = {'retrieved_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'source': 'https://api.osv.dev/v1/querybatch',
        'dependency_source': args.components.name, 'coordinate_count': len(packages),
        'finding_count': len(findings), 'findings': findings, 'components': packages,
        'coverage_limit': 'Public resolved Maven versions only, not exploitability, shaded libraries, historic commits, device hardening or an exhaustive SCA certification.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'Queried {len(packages)} public coordinates; advisory version matches: {len(findings)}')
    return 1 if findings else 0


if __name__ == '__main__':
    raise SystemExit(main())
