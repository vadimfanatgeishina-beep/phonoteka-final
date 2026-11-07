"""Offline tests. No remote queries or private values."""
import io
import json
import unittest
from unittest.mock import patch
from query_public_dependency_advisories import coordinates, query


class PublicCoordinatesTest(unittest.TestCase):
    def test_deduplicates_resolved_versions(self):
        component = {'group': 'example', 'name': 'parser', 'version': '1.0'}
        self.assertEqual(len(coordinates([component, component])), 1)

    def test_empty_inventory_fails_closed(self):
        with self.assertRaises(ValueError):
            coordinates([])

    def test_conflicting_versions_fail_closed(self):
        with self.assertRaises(ValueError):
            coordinates([{'group': 'example', 'name': 'parser', 'version': v} for v in ['1', '2']])

    def test_response_cardinality_fails_closed(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'{"results":[]}')):
            with self.assertRaises(ValueError):
                query([{'ecosystem': 'Maven', 'name': 'example:parser', 'version': '1'}])

    def test_only_coordinates_sent_and_findings_preserved(self):
        packages = [{'ecosystem': 'Maven', 'name': 'example:parser', 'version': '1'}]
        response = io.BytesIO(json.dumps({'results': [{'vulns': [{'id': 'TEST-OWNED'}]}]}).encode())
        with patch('urllib.request.urlopen', return_value=response) as open_request:
            findings = query(packages)
        self.assertEqual(findings[0]['id'], 'TEST-OWNED')
        request = open_request.call_args.args[0]
        self.assertEqual(json.loads(request.data), {'queries': [{'package': {'ecosystem': 'Maven', 'name': 'example:parser'}, 'version': '1'}]})

    def test_api_error_not_clean_result(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'{"results":[{"error":"unavailable"}]}')):
            with self.assertRaises(ValueError):
                query([{'ecosystem': 'Maven', 'name': 'example:parser', 'version': '1'}])


if __name__ == '__main__':
    unittest.main()
