#!/usr/bin/env python3
"""Tests for Bosca personalized-model selection discovery."""

import unittest
from unittest.mock import MagicMock

from bosca_selection import BoscaModelSelectionClient, _positive_version


class TestPositiveVersion(unittest.TestCase):

    def test_accepts_only_positive_whole_numbers(self):
        self.assertEqual(_positive_version(3), 3)
        self.assertEqual(_positive_version(4.0), 4)
        self.assertIsNone(_positive_version(True))
        self.assertIsNone(_positive_version(0))
        self.assertIsNone(_positive_version(2.5))
        self.assertIsNone(_positive_version("3"))


class TestBoscaModelSelectionClient(unittest.TestCase):

    def setUp(self):
        self.client = BoscaModelSelectionClient("http://bosca:8080", "token")
        self.client.session = MagicMock()

    def _response(self, data):
        response = MagicMock()
        response.json.return_value = {"data": data}
        self.client.session.post.return_value = response
        return response

    def test_reads_authorized_selection(self):
        response = self._response({
            "recommendation": {"modelSelection": {"personalizedVersions": [5, 7, 8, 7]}},
        })
        self.assertEqual(self.client.get_selected_personalized_versions(), {5, 7, 8})
        response.raise_for_status.assert_called_once()
        _, kwargs = self.client.session.post.call_args
        self.assertEqual(kwargs["timeout"], 10.0)
        self.assertNotIn("featureFlags", kwargs["json"]["query"])
        self.assertNotIn("strategies", kwargs["json"]["query"])

    def test_empty_selection(self):
        self._response({"recommendation": {"modelSelection": {"personalizedVersions": []}}})
        self.assertEqual(self.client.get_selected_personalized_versions(), set())

    def test_invalid_selection_fails_refresh(self):
        for values in (None, [True], [0], ["3"], [2.5]):
            with self.subTest(values=values):
                self._response({"recommendation": {"modelSelection": {"personalizedVersions": values}}})
                with self.assertRaises(RuntimeError):
                    self.client.get_selected_personalized_versions()

    def test_graphql_errors_fail_selection_refresh(self):
        response = MagicMock()
        response.json.return_value = {"errors": [{"message": "forbidden"}]}
        self.client.session.post.return_value = response

        with self.assertRaisesRegex(RuntimeError, "forbidden"):
            self.client.get_selected_personalized_versions()


if __name__ == "__main__":
    unittest.main()
