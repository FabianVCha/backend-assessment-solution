import json
from datetime import datetime, timezone
from pathlib import Path
import stat
from tempfile import TemporaryDirectory
import unittest

from console import ApiError, InputError
from dashboard import Dashboard, METRICS, save_report, search_filter


class FakeApi:
    def __init__(self):
        self.requests = []

    def request(self, path, **kwargs):
        self.requests.append((path, kwargs))
        if path == "/overview":
            return {key: 1 for _, key in METRICS}
        if path == "/notifications":
            return {"items": [{"provider": "ANDINA", "event_id": "e/1"}], "next_cursor": "opaque"}
        return {"event": {}, "jobs": [], "timeline": []}


class DashboardTest(unittest.TestCase):
    def test_inline_search_translates_explicit_and_short_queries(self):
        self.assertEqual({"country": "CO"}, search_filter("co"))
        self.assertEqual({"provider": "ANDINA"}, search_filter("provider:andina"))
        self.assertEqual({"payment_key": "AN-CO-1"}, search_filter("payment:AN-CO-1"))
        self.assertEqual({"event_id": "id:q"}, search_filter("event:id:q"))
        self.assertEqual({}, search_filter(""))
        with self.assertRaises(InputError):
            search_filter("country:US")

    def test_inline_search_keeps_the_terminal_and_resets_pagination(self):
        api = FakeApi()
        dash = Dashboard(api)
        dash.cursors = [None, "prior"]
        dash.handle("/")
        for key in "event:query":
            dash.handle(key)
        dash.handle("\n")
        self.assertEqual(1, dash.tab)
        self.assertEqual([None], dash.cursors)
        self.assertEqual({"limit": "15", "event_id": "query"}, api.requests[-1][1]["query"])
        dash.handle("/")
        dash.handle("q")
        dash.handle("\x1b")
        self.assertIsNone(dash.search)
        self.assertEqual(1, len(api.requests))

    def test_navigation_uses_opaque_cursor_and_encodes_selected_id(self):
        api = FakeApi()
        dash = Dashboard(api)
        dash.handle("2")
        dash.handle("n")
        self.assertEqual("opaque", api.requests[-1][1]["query"]["cursor"])
        dash.handle("p")
        self.assertNotIn("cursor", api.requests[-1][1]["query"])
        dash.handle("\n")
        self.assertEqual("/notifications/ANDINA/e%2F1", api.requests[-1][0])
        self.assertEqual(2, dash.tab)
        dash.handle("\x1b")
        self.assertEqual(1, dash.tab)

    def test_failed_refresh_clears_old_overview_before_next_export(self):
        class FailingApi(FakeApi):
            def request(self, path, **kwargs):
                raise ApiError("Offline")

        dash = Dashboard(FailingApi())
        dash.metrics = {"accepted": 99}
        with self.assertRaises(ApiError):
            dash.refresh()
        self.assertIsNone(dash.metrics)

    def test_report_is_aggregate_only_private_and_does_not_overwrite(self):
        values = {key: 2 for _, key in METRICS}
        stamp = datetime(2026, 1, 1, tzinfo=timezone.utc)
        with TemporaryDirectory() as temp:
            target = Path(temp) / "reports"
            report = save_report(target, {**values, "token": "never-save", "event_id": "secret-id"}, "CO", stamp)
            self.assertEqual(report.parent, target.resolve())
            self.assertEqual(json.loads(report.read_text()), {
                "scope": "CO", "exported_at": stamp.isoformat(), "metrics": values,
            })
            self.assertEqual(stat.S_IMODE(report.stat().st_mode), 0o600)
            with self.assertRaises(FileExistsError):
                save_report(target, values, "CO", stamp)

    def test_incomplete_snapshot_does_not_even_create_the_folder(self):
        with TemporaryDirectory() as temp:
            target = Path(temp) / "reports"
            with self.assertRaises(InputError):
                save_report(target, {"accepted": "invalid"}, "")
            self.assertFalse(target.exists())
