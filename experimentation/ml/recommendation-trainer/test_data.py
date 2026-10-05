#!/usr/bin/env python3
"""Unit tests for trainer.data — the Bosca analytics GraphQL loader (requests mocked, no network)."""

import unittest
from datetime import timedelta
from unittest.mock import MagicMock, patch

import numpy as np
import pandas as pd
import requests

from trainer import data


def _response(json_body):
    resp = MagicMock()
    resp.raise_for_status = MagicMock()
    resp.json.return_value = json_body
    return resp


def _records_page(records):
    return {"analytics": {"queries": {"executeByKey": {"records": records}}}}


def _chunk(content_id, chunk_index, token_start, token_end, embedding, aggregation_weight=None):
    token_count = token_end - token_start
    return {
        "content_id": content_id,
        "chunk_index": chunk_index,
        "token_start": token_start,
        "token_end": token_end,
        "token_count": token_count,
        "aggregation_weight": token_count if aggregation_weight is None else aggregation_weight,
        "embedding": embedding,
    }


class TestBoscaGraphql(unittest.TestCase):

    @patch.object(data.requests, "post")
    def test_invalid_json_preserves_error_and_identifies_response_and_page(self, mock_post):
        for payload, content_type in [(b"", "application/json"), (b"<html>upstream failure</html>", "text/html")]:
            with self.subTest(payload=payload):
                response = requests.Response()
                response.status_code = 200
                response._content = payload
                response.headers.update({"Content-Type": content_type, "CF-Ray": "test-ray"})
                response.elapsed = timedelta(seconds=6.9)
                mock_post.return_value = response
                with self.assertRaises(requests.exceptions.JSONDecodeError) as caught:
                    data._bosca_graphql("http://bosca:8080", "tok", data._EXECUTE_QUERY, {
                        "k": "recommender-interactions",
                        "p": [
                            {"parameter": "offset", "value": 5000},
                            {"parameter": "limit", "value": 5000},
                            {"parameter": "asOf", "value": "2026-09-08T14:00:00Z"},
                        ],
                    })
                note = caught.exception.__notes__[0]
                for expected in ("recommender-interactions", "'offset': 5000", "'limit': 5000",
                                 "2026-09-08T14:00:00Z", "status=200", content_type,
                                 "test-ray", "elapsed_seconds=6.900", f"bytes={len(payload)}",
                                 f"body_prefix={payload.decode()!r}"):
                    self.assertIn(expected, note)

    @patch.object(data.requests, "post")
    def test_http_error_preserved_and_body_preview_redacted_and_bounded(self, mock_post):
        response = requests.Response()
        response.status_code = 502
        response._content = ("Bearer secret-token " + "x" * 1000).encode()
        mock_post.return_value = response
        with self.assertRaises(requests.HTTPError) as caught:
            data._bosca_graphql("http://bosca:8080", "secret-token", "query")
        self.assertIs(caught.exception.response, response)
        note = caught.exception.__notes__[0]
        self.assertIn("status=502", note)
        self.assertIn("[REDACTED]", note)
        self.assertNotIn("secret-token", note)
        self.assertNotIn("x" * 513, note)

    @patch.object(data.requests, "post")
    def test_returns_data_and_sends_auth_header(self, mock_post):
        mock_post.return_value = _response({"data": {"ok": 1}})
        result = data._bosca_graphql("http://bosca:8080/", "tok", "query", {"v": 1})
        self.assertEqual(result, {"ok": 1})
        _, kwargs = mock_post.call_args
        self.assertEqual(kwargs["headers"]["Authorization"], "Bearer tok")
        self.assertEqual(kwargs["json"]["variables"], {"v": 1})
        self.assertEqual(kwargs["timeout"], data._REQUEST_TIMEOUT)

    @patch.object(data.requests, "post")
    def test_request_timeout_preserves_failure_and_identifies_page(self, mock_post):
        failure = requests.ReadTimeout("server stopped responding")
        mock_post.side_effect = failure
        with self.assertRaises(requests.ReadTimeout) as caught:
            data._bosca_graphql("http://bosca:8080", "secret-token", data._EXECUTE_QUERY, {
                "k": "recommender-interactions",
                "p": [{"parameter": "offset", "value": 20000}, {"parameter": "limit", "value": 20000}],
            })
        self.assertIs(caught.exception, failure)
        note = caught.exception.__notes__[0]
        for expected in ("recommender-interactions", "'offset': 20000", "'limit': 20000",
                         "connect_timeout_seconds=", "read_timeout_seconds=", "elapsed_seconds="):
            self.assertIn(expected, note)
        self.assertNotIn("secret-token", note)
        mock_post.assert_called_once()

    @patch.object(data.requests, "post")
    def test_no_token_omits_auth_header(self, mock_post):
        mock_post.return_value = _response({"data": {}})
        data._bosca_graphql("http://bosca:8080", None, "query")
        _, kwargs = mock_post.call_args
        self.assertNotIn("Authorization", kwargs["headers"])

    @patch.object(data.requests, "post")
    def test_raises_on_graphql_errors(self, mock_post):
        mock_post.return_value = _response({"errors": [{"message": "boom"}]})
        with self.assertRaises(RuntimeError):
            data._bosca_graphql("http://bosca:8080", None, "query")


class TestExecutePaged(unittest.TestCase):

    @patch.object(data, "_bosca_graphql")
    def test_single_short_page_stops(self, mock_gql):
        mock_gql.return_value = _records_page([{"a": 1}, {"a": 2}])
        df = data._execute_paged("http://x", None, "k", ["a"])
        self.assertEqual(len(df), 2)
        self.assertEqual(mock_gql.call_count, 1)  # short page -> one request

    @patch.object(data, "_bosca_graphql")
    def test_pages_until_short_page(self, mock_gql):
        full = [{"a": i} for i in range(data._PAGE_SIZE)]  # exactly one full page -> keep going
        tail = [{"a": -1}]
        mock_gql.side_effect = [_records_page(full), _records_page(tail)]
        with self.assertLogs(data.log, level="INFO") as logs, patch.object(
            data, "perf_counter", side_effect=[10.0, 12.5, 13.0, 16.0],
        ):
            df = data._execute_paged("http://x", None, "k", ["a"])
        self.assertEqual(len(df), data._PAGE_SIZE + 1)
        self.assertEqual(mock_gql.call_count, 2)
        # second call advanced the offset by one page
        second_params = mock_gql.call_args_list[1].args[3]["p"]
        self.assertEqual(second_params[0], {"parameter": "offset", "value": data._PAGE_SIZE})
        messages = [record.getMessage() for record in logs.records]
        self.assertEqual(messages, [
            f"Loading k page: offset=0 limit={data._PAGE_SIZE}",
            f"Loaded k page: offset=0 rows={data._PAGE_SIZE} total_rows={data._PAGE_SIZE} elapsed_seconds=2.500",
            f"Loading k page: offset={data._PAGE_SIZE} limit={data._PAGE_SIZE}",
            f"Loaded k page: offset={data._PAGE_SIZE} rows=1 total_rows={data._PAGE_SIZE + 1} elapsed_seconds=3.000",
        ])

    @patch.object(data, "_bosca_graphql")
    def test_logs_request_before_waiting_and_preserves_request_failure(self, mock_gql):
        failure = requests.ConnectionError("connection interrupted")
        with self.assertLogs(data.log, level="INFO") as logs:
            def fail_request(*args):
                self.assertEqual([record.getMessage() for record in logs.records], [
                    f"Loading recommender-interactions page: offset=0 limit={data._PAGE_SIZE}",
                ])
                raise failure

            mock_gql.side_effect = fail_request
            with self.assertRaises(requests.ConnectionError) as caught:
                data._execute_paged("http://x", "secret-token", "recommender-interactions", ["a"])

        self.assertIs(caught.exception, failure)
        self.assertEqual(len(logs.records), 1)

    @patch.object(data, "_bosca_graphql")
    def test_logs_empty_page_completion(self, mock_gql):
        mock_gql.return_value = _records_page([])
        with self.assertLogs(data.log, level="INFO") as logs:
            frame = data._execute_paged("http://x", None, "k", ["a"])
        self.assertTrue(frame.empty)
        self.assertEqual(frame.columns.tolist(), ["a"])
        self.assertIn("offset=0 rows=0 total_rows=0", logs.records[-1].getMessage())


class TestLoadData(unittest.TestCase):

    @patch.object(data, "_execute_pooled_embeddings")
    @patch.object(data, "_execute_paged")
    def test_loads_all_datasets_and_fuses_embeddings(self, mock_paged, mock_embeddings):
        # Return a one-row frame carrying exactly the columns each ordinary query declares; embeddings are
        # streamed and pooled by their dedicated loader.
        def frame_for(url, token, key, columns, as_of=None):
            row = {
                c: (
                    ["default"] if c == "recommendation_contexts"
                    else ["featured"] if c == "labels"
                    else "c0"
                )
                for c in columns
            }
            return pd.DataFrame([row])

        mock_paged.side_effect = frame_for
        mock_embeddings.return_value = pd.DataFrame({"content_id": ["c0"], "embedding": [[0.6, 0.8]]})
        users, interactions, content, categories, signals, feedback = data.load_data_from_bosca("http://x", "tok")
        self.assertEqual(mock_paged.call_count, 7)
        mock_embeddings.assert_called_once_with("http://x", "tok")
        called_keys = [call.args[2] for call in mock_paged.call_args_list]
        self.assertEqual(called_keys[0], "recommender-users")
        self.assertEqual(called_keys[1], "recommender-interactions")
        self.assertEqual(called_keys[2], "recommender-content-features")
        self.assertEqual(called_keys[4], "recommender-user-signals")
        self.assertEqual(called_keys[6], "recommender-guide-completions")
        self.assertEqual(len(interactions), 2)
        self.assertIn("user_id", users.columns)
        self.assertIn("interaction_created", interactions.columns)
        self.assertIn("visibility_threshold", interactions.columns)
        self.assertIn("feedback_created", feedback.columns)
        event_cutoff = mock_paged.call_args_list[1].kwargs["as_of"]
        self.assertEqual(mock_paged.call_args_list[5].kwargs["as_of"], event_cutoff)
        self.assertEqual(mock_paged.call_args_list[6].kwargs["as_of"], event_cutoff)
        self.assertEqual(interactions.attrs["as_of"], event_cutoff)
        self.assertEqual(content["embedding"].iloc[0], [0.6, 0.8])
        self.assertEqual(content["recommendation_contexts"].iloc[0], ["default"])
        self.assertEqual(content["labels"].iloc[0], ["featured"])


class TestExecutePooledEmbeddings(unittest.TestCase):

    @patch.object(data, "_EMBEDDING_PAGE_SIZE", 2)
    @patch.object(data, "_bosca_graphql")
    def test_pages_by_content_id_and_pools_overlap_without_duplicate_weight(self, mock_gql):
        mock_gql.side_effect = [
            _records_page([
                _chunk("c0", 0, 0, 2, "[1.0, 0.0]", 1.5),
                _chunk("c0", 1, 1, 3, "[0.0, 1.0]", 1.5),
                _chunk("c1", 0, 0, 1, "[0.0, 2.0]"),
            ]),
            _records_page([_chunk("c2", 0, 0, 1, "[2.0, 0.0]")]),
        ]

        with self.assertLogs(data.log, level="INFO") as logs, patch.object(
            data, "perf_counter", side_effect=[10.0, 12.5, 13.0, 16.0],
        ):
            pooled = data._execute_pooled_embeddings("http://x", None)

        self.assertEqual(pooled["content_id"].tolist(), ["c0", "c1", "c2"])
        np.testing.assert_allclose(pooled["embedding"].iloc[0], [0.70710677, 0.70710677], rtol=1e-6)
        np.testing.assert_allclose(pooled["embedding"].iloc[1], [0.0, 1.0], rtol=1e-6)
        self.assertEqual(mock_gql.call_args_list[1].args[3]["p"][0], {"parameter": "offset", "value": 2})
        self.assertEqual([record.getMessage() for record in logs.records], [
            "Loading recommender-content-embeddings page: content_offset=0 content_limit=2",
            "Loaded recommender-content-embeddings page: content_offset=0 chunks=3 content_items=2 "
            "total_content_items=2 elapsed_seconds=2.500",
            "Loading recommender-content-embeddings page: content_offset=2 content_limit=2",
            "Loaded recommender-content-embeddings page: content_offset=2 chunks=1 content_items=1 "
            "total_content_items=3 elapsed_seconds=3.000",
        ])

    @patch.object(data, "_bosca_graphql")
    def test_uses_the_embedding_specific_document_page_size(self, mock_gql):
        mock_gql.return_value = _records_page([])

        data._execute_pooled_embeddings("http://x", None)

        params = mock_gql.call_args.args[3]["p"]
        self.assertEqual(params[1], {"parameter": "limit", "value": 500})

    @patch.object(data, "_bosca_graphql")
    def test_rejects_malformed_embedding_chunks(self, mock_gql):
        invalid_records = [
            {**_chunk("c0", 0, 0, 2, "[3.0, 4.0]"), "token_count": "bad"},
            {**_chunk("c0", 0, 0, 2, "[3.0, 4.0]"), "chunk_index": 0.5},
            _chunk("c0", 0, 0, 2, "not-json"),
            _chunk("c0", 0, 0, 2, "[NaN, 1.0]"),
            {**_chunk("c0", 0, 0, 2, "[3.0, 4.0]"), "aggregation_weight": 3},
        ]
        for record in invalid_records:
            with self.subTest(record=record):
                mock_gql.reset_mock()
                mock_gql.return_value = _records_page([record])
                with self.assertRaises(ValueError):
                    data._execute_pooled_embeddings("http://x", None)

    @patch.object(data, "_bosca_graphql")
    def test_rejects_mismatched_dimensions_and_noncontiguous_indexes(self, mock_gql):
        invalid_pages = [
            [
                _chunk("c0", 0, 0, 1, "[1.0, 0.0]"),
                _chunk("c0", 1, 1, 2, "[1.0]"),
            ],
            [_chunk("c0", 1, 0, 1, "[1.0, 0.0]")],
            [
                _chunk("c0", 0, 0, 2, "[1.0, 0.0]"),
                _chunk("c0", 1, 3, 4, "[1.0, 0.0]"),
            ],
        ]
        for records in invalid_pages:
            with self.subTest(records=records):
                mock_gql.reset_mock()
                mock_gql.return_value = _records_page(records)
                with self.assertRaises(ValueError):
                    data._execute_pooled_embeddings("http://x", None)


class TestMergeGuideCompletions(unittest.TestCase):

    def row(self, key, content, kind, *, saved=False, started="2026-09-01T00:00:00Z",
            created="2026-09-05T12:00:00Z", user="u"):
        return dict(event_id=key, user_id=user, content_id=content, element_type=kind,
                    app_id="bosca-guide-state" if saved else "bosca-guide-completions",
                    interaction_type="Completion", guide_id="guide", guide_version="1",
                    guide_started=started, interaction_created=created)

    def test_exact_steps_replace_active_state_for_parent_and_child(self):
        exact = pd.DataFrame([self.row("step", "child", "guide_step"),
                              self.row("step", "guide", "guide_step")])
        saved = pd.DataFrame([self.row("fallback-child", "child", "guide_step", saved=True),
                              self.row("fallback-parent", "guide", "guide_progress", saved=True)])
        result = data._merge_guide_completions(exact, saved)
        self.assertEqual(result.event_id.tolist(), ["step", "step"])

    def test_completed_history_links_to_exact_run_and_keeps_unrecorded_older_steps(self):
        exact = pd.DataFrame([self.row("complete", "guide", "guide"),
                              self.row("step", "child", "guide_step", created="2026-09-03T00:00:00Z")])
        saved = pd.DataFrame([
            self.row("fallback-guide", "guide", "guide", saved=True, started=None,
                     created="2026-09-05T12:00:00.000123Z"),
            self.row("fallback-child", "child", "guide_step", saved=True, started=None),
            self.row("legacy-child", "older-child", "guide_step", saved=True, started=None),
            self.row("older-run", "guide", "guide", saved=True, started=None, created="2026-08-01T00:00:00Z"),
            self.row("other-user", "child", "guide_step", saved=True, user="other"),
        ])
        result = data._merge_guide_completions(exact, saved)
        self.assertEqual(result.event_id.tolist(), ["complete", "step", "legacy-child", "older-run", "other-user"])

    def test_new_run_and_malformed_event_do_not_suppress_state(self):
        exact = pd.DataFrame([self.row("old", "child", "guide_step", started="2026-08-01T00:00:00Z"),
                              self.row("malformed", "child", "guide_step", started=None)])
        saved = pd.DataFrame([self.row("current", "child", "guide_step", saved=True)])
        result = data._merge_guide_completions(exact, saved)
        self.assertEqual(result.event_id.tolist(), ["old", "malformed", "current"])
        self.assertEqual(len(data._merge_guide_completions(exact, saved.iloc[:0])), 2)
        self.assertEqual(len(data._merge_guide_completions(exact.iloc[:0], saved)), 1)


class TestMergeEmbeddings(unittest.TestCase):

    def test_attaches_pooled_vectors_without_duplicating_content(self):
        content = pd.DataFrame({"content_id": ["c0", "c1"], "content_type": ["t", "t"]})
        embeddings = pd.DataFrame({
            "content_id": ["c0"],
            "embedding": [np.asarray([0.6, 0.8], dtype=np.float32)],
        })
        merged = data._merge_embeddings(content, embeddings)
        c0 = merged.loc[merged["content_id"] == "c0"].iloc[0]
        np.testing.assert_allclose(c0["embedding"], [0.6, 0.8], rtol=1e-6)
        self.assertTrue(pd.isna(merged.loc[merged["content_id"] == "c1", "embedding"].iloc[0]))

    def test_no_embeddings_is_noop(self):
        content = pd.DataFrame({"content_id": ["c0"], "content_type": ["t"]})
        empty = pd.DataFrame(columns=["content_id", "embedding"])
        merged = data._merge_embeddings(content, empty)
        self.assertNotIn("embedding", merged.columns)
        pd.testing.assert_frame_equal(merged, content)


if __name__ == "__main__":
    unittest.main()


def test_api_epoch_milliseconds_preserve_recent_feedback_weight_and_future_cutoff():
    from trainer.observations import prepare_observations
    cutoff = pd.Timestamp('2026-09-09T18:00:00Z')
    recent = cutoff - pd.Timedelta(hours=1)
    records = [dict(user_id='u', content_id=item, feedback_source='rating', feedback_label=1.,
                    feedback_created=timestamp)
               for item, timestamp in [('recent', int(recent.timestamp() * 1000)),
                                       ('iso', recent.isoformat()),
                                       ('future', int((cutoff + pd.Timedelta(hours=1)).timestamp() * 1000))]]
    with patch.object(data, '_bosca_graphql', return_value=_records_page(records)):
        frame = data._execute_paged('http://local', None, 'recommender-feedback', list(records[0]))
    assert frame.feedback_created.iloc[0] == recent
    assert frame.feedback_created.iloc[1] == recent
    observations, report = prepare_observations(pd.DataFrame(), frame, ['recent', 'iso', 'future'], as_of=cutoff)
    assert set(observations.content_id) == {'recent', 'iso'}
    assert observations.sample_weight.between(4.9, 5.).all()
    assert report['total_weight'] > 9.8
    assert report['invalid_feedback_rows'] == 1


def test_api_timestamps_normalize_events_and_guide_starts_and_preserve_missing_values():
    timestamp = pd.Timestamp('2026-09-09T18:00:00Z')
    records = [dict(interaction_created=int(timestamp.timestamp() * 1000), guide_started=timestamp.isoformat()),
               dict(interaction_created=None, guide_started='invalid')]
    with patch.object(data, '_bosca_graphql', return_value=_records_page(records)):
        frame = data._execute_paged('http://local', None, 'recommender-interactions', list(records[0]))
    assert frame.interaction_created.iloc[0] == timestamp
    assert frame.guide_started.iloc[0] == timestamp
    assert pd.isna(frame.interaction_created.iloc[1])
    assert pd.isna(frame.guide_started.iloc[1])


def test_all_numeric_api_timestamps_preserve_millisecond_precision():
    timestamp = pd.Timestamp('2026-09-09T17:38:14.581Z')
    records = [dict(feedback_created=int(timestamp.timestamp() * 1000))]
    with patch.object(data, '_bosca_graphql', return_value=_records_page(records)):
        frame = data._execute_paged('http://local', None, 'recommender-feedback', ['feedback_created'])
    assert frame.feedback_created.iloc[0] == timestamp
