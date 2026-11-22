import json
import re
import unittest
from collections import Counter

from tools.local_demo import bulk_fixtures


class LocalDemoSeedTest(unittest.TestCase):
    def test_bulk_seed_creates_unique_fixtures_across_all_providers(self):
        fixtures = bulk_fixtures(200)

        self.assertEqual(len(fixtures), 200)
        self.assertEqual(
            Counter(provider for _, provider, _, _ in fixtures),
            Counter({"QUETZAL": 67, "ANDINA": 67, "AUSTRAL": 66}),
        )

        identities = []
        for country, provider, body, secret in fixtures:
            self.assertTrue(country)
            self.assertTrue(secret)
            payload = json.loads(body)
            identities.append((provider, json.dumps(payload, sort_keys=True)))
            if provider == "QUETZAL":
                policy = payload["data"]["charge"]["reference"]
            elif provider == "ANDINA":
                policy = payload["transaccion"]["referencia"]
            else:
                policy = payload["payment"]["policyNumber"]
            self.assertRegex(policy, rf"POL-{country}-\d{{9}}")

        self.assertEqual(len(identities), len(set(identities)))

    def test_bulk_seed_rejects_invalid_counts(self):
        with self.assertRaises(ValueError):
            bulk_fixtures(0)
        with self.assertRaises(ValueError):
            bulk_fixtures(1001)


if __name__ == "__main__":
    unittest.main()
