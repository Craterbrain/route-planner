"""Unit tests: tools/addrnorm.py against the shared vectors in
tools/address-normalize-cases.json (the Kotlin port must pass the same file).

    python3 -m unittest tools/test_normalize.py     (or: python3 tools/test_normalize.py)
"""

import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from addrnorm import normalize_street, normalize_number, normalize_place  # noqa: E402


class NormalizeStreetTest(unittest.TestCase):
    def test_shared_vectors(self):
        with open(os.path.join(HERE, "address-normalize-cases.json"), encoding="utf-8") as f:
            cases = json.load(f)
        self.assertGreaterEqual(len(cases), 80)
        for c in cases:
            with self.subTest(input=c["input"]):
                self.assertEqual(normalize_street(c["input"]), c["norm"])

    def test_idempotent(self):
        with open(os.path.join(HERE, "address-normalize-cases.json"), encoding="utf-8") as f:
            cases = json.load(f)
        for c in cases:
            with self.subTest(norm=c["norm"]):
                self.assertEqual(normalize_street(c["norm"]), c["norm"])

    def test_number(self):
        self.assertEqual(normalize_number("8681"), "8681")
        self.assertEqual(normalize_number("12-B"), "12b")
        self.assertEqual(normalize_number(" 12 1/2 "), "121/2")

    def test_place(self):
        self.assertEqual(normalize_place("Mount Juliet"), ["mt", "juliet"])
        self.assertEqual(normalize_place("CRAB ORCHARD"), ["crab", "orchard"])


if __name__ == "__main__":
    unittest.main()
