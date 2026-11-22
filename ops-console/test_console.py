import contextlib
import io
import json
import os
import threading
import unittest
import warnings
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

from console import Api, Console, getpass, loopback_address, main


TOKEN = "test-only-secret"
EVENT = {
    "provider": "ANDINA", "country": "CO", "event_id": "event /+?#é",
    "status": "PAID", "payment_key": "pay + one",
    "occurred_at": "2026-01-01T00:00:00Z", "received_at": "2026-01-01T00:00:01Z",
}


class LocalHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def handle_request(self):
        size = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(size)) if size else None
        self.server.requests.append((self.command, self.path, self.headers, body))
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            status, result, headers = 401, {"error": {"code": "UNAUTHENTICATED"}}, {}
        else:
            status, result, headers = self.server.responses.popleft()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        for name, value in headers.items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(result if isinstance(result, bytes) else json.dumps(result).encode())

    do_GET = do_POST = handle_request


class ConsoleTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), LocalHandler)
        self.server.requests = []
        self.server.responses = deque()
        self.thread = threading.Thread(
            target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True,
        )
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.thread.join)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def reply(self, status, result, headers=None):
        self.server.responses.append((status, result, headers or {}))

    def run_console(self, *entries, token=TOKEN):
        answers = iter((*entries, "q"))
        out = []
        Console(Api(self.url, token), read=lambda _: next(answers), write=out.append).run()
        return "\n".join(out)

    def test_overview_and_all_filters_with_opaque_cursor(self):
        self.reply(200, {"accepted": 0, "unresolved_work": 0, "last_update_at": None})
        self.reply(200, {"items": [EVENT], "next_cursor": "opaque+/=&"})
        self.reply(200, {"items": [], "next_cursor": None})
        out = self.run_console("1", "CO", "2", "andina", "co", EVENT["event_id"], EVENT["payment_key"], "1", "y")
        self.assertIn("accepted: 0", out)
        self.assertIn("Page 1 (1 notifications)", out)
        self.assertIn("Page 2 (0 notifications)", out)
        request_paths = [path for _, path, _, _ in self.server.requests]
        self.assertEqual(request_paths[0], "/ops/v1/overview?country=CO")
        self.assertEqual(urlsplit(request_paths[1]).path, "/ops/v1/notifications")
        filters = {
            "provider": ["ANDINA"], "country": ["CO"], "event_id": [EVENT["event_id"]],
            "payment_key": [EVENT["payment_key"]], "limit": ["1"],
        }
        self.assertEqual(parse_qs(urlsplit(request_paths[1]).query), filters)
        self.assertEqual(parse_qs(urlsplit(request_paths[2]).query), dict(filters, cursor=["opaque+/=&"]))

    def test_detail_path_timeline_and_terminal_output(self):
        event = dict(EVENT, status="PAID\x1b[2J\r\n\u009b2J\u202e" + TOKEN, raw="excluded payload")
        self.reply(200, {
            "event": event,
            "jobs": [{"destination": "SALESFORCE", "state": "HELD", "attempts": 2}],
            "timeline": [
                {"type": "RECEIVED", "event_time": EVENT["occurred_at"], "processing_time": EVENT["received_at"]},
                {"type": "HELD", "event_time": EVENT["occurred_at"], "processing_time": None},
            ],
        })
        out = self.run_console("3", "ANDINA", EVENT["event_id"])
        self.assertEqual(self.server.requests[0][1], "/ops/v1/notifications/ANDINA/event%20%2F%2B%3F%23%C3%A9")
        self.assertIn("destination: SALESFORCE | state: HELD | attempts: 2", out)
        self.assertIn("type: RECEIVED | event_time: 2026-01-01T00:00:00Z | processing_time: 2026-01-01T00:00:01Z", out)
        self.assertIn("type: HELD | event_time: 2026-01-01T00:00:00Z | processing_time: -", out)
        for forbidden in ("\x1b", "\r", "\u009b", "\u202e", TOKEN, "excluded payload"):
            self.assertNotIn(forbidden, out)
        self.assertIn(r"\x1b[2J\r\n\x9b2J\u202e[redacted]", out)

    def test_replay_validation_and_confirmation_never_send_a_request(self):
        prefix = ("4", "ANDINA", "event-1", "CANONICAL_EVENT")
        out = self.run_console(*prefix, " ", *prefix, "operator reason", "key-1", "yes")
        self.assertIn("Reason must be nonblank", out)
        self.assertIn("Replay canceled.", out)
        self.assertEqual(self.server.requests, [])

    def test_replay_generates_key_and_can_retry_exact_request(self):
        self.reply(409, {"error": {"code": "JOB_CLAIMED"}})
        self.reply(200, {"outcome": "REQUEUED"})
        prefix = ("4", "ANDINA", "event-1", "SALESFORCE", "INC-1")
        out = self.run_console(*prefix, "", "REPLAY")
        method, path, headers, body = self.server.requests[0]
        key = body["idempotency_key"]
        self.assertRegex(key, r"^[0-9a-f-]{36}$")
        self.assertIn(f"idempotency_key: {key}", out)
        self.assertIn("Job is claimed", out)
        self.assertEqual((method, path, headers.get("Content-Type")),
                         ("POST", "/ops/v1/replays", "application/json"))
        self.assertEqual(body, {
            "provider": "ANDINA", "event_id": "event-1", "destination": "SALESFORCE",
            "reason": "INC-1", "idempotency_key": key,
        })
        self.assertEqual(len(self.server.requests), 1)
        out = self.run_console(*prefix, key, "REPLAY")
        self.assertEqual(self.server.requests[1][3], body)
        self.assertIn("REQUEUED: the replay was accepted; delivery is not confirmed.", out)

    def test_auth_roles_scope_and_non_disclosure(self):
        self.assertIn("Authentication failed (401)", self.run_console("1", "", token="invalid"))
        self.reply(403, {"error": {"code": TOKEN}})
        self.reply(403, {"error": {"code": TOKEN}})
        self.reply(404, {"error": {"code": TOKEN}})
        out = self.run_console(
            "1", "", "4", "ANDINA", "event-1", "SALESFORCE", "reason", "key", "REPLAY",
            "3", "ANDINA", "event-1",
        )
        self.assertIn("token's read role", out)
        self.assertIn("token's replay role", out)
        self.assertIn("absent or outside your country scope", out)
        self.assertNotIn(TOKEN, out)

    def test_replay_conflicts_and_unknown_outcome(self):
        prefix = ("4", "ANDINA", "event-1", "SALESFORCE", "INC-1", "key-1", "REPLAY")
        for code, message in (
            ("KEY_TARGET_CONFLICT", "key already belongs to a different target"),
            ("RECONCILIATION_REQUIRED", "requires reconciliation"),
            ("JOB_NOT_REPLAYABLE", "not eligible for replay"),
        ):
            with self.subTest(code=code):
                self.reply(409, {"error": {"code": code}})
                self.assertIn(message, self.run_console(*prefix))
        self.reply(200, b"")
        out = self.run_console(*prefix)
        self.assertIn("Replay outcome is unknown", out)
        self.assertIn("reuse the same key", out)
        self.assertEqual(len(self.server.requests), 4)

    def test_redirects_are_not_followed_and_environment_proxies_are_ignored(self):
        self.reply(302, {}, {"Location": self.url + "/redirect-target"})
        with patch.dict(os.environ, {"http_proxy": "http://127.0.0.1:1", "no_proxy": ""}):
            out = self.run_console("1", "")
        self.assertIn("Redirects are not followed", out)
        self.assertEqual(len(self.server.requests), 1)

    def test_unavailable_and_malformed_overview_do_not_show_zero_counts(self):
        self.reply(503, {"error": {"code": "STORE_UNAVAILABLE"}})
        self.reply(200, b"not JSON")
        out = self.run_console("1", "", "1", "")
        self.assertIn("operations store is unavailable", out)
        self.assertIn("Unexpected response", out)
        self.assertNotIn("accepted:", out)


class BoundaryTest(unittest.TestCase):
    def test_only_literal_loopback_origins(self):
        self.assertEqual(loopback_address("http://127.0.0.1:8080"), ("127.0.0.1", 8080))
        self.assertEqual(loopback_address("http://[::1]:8080/"), ("::1", 8080))
        for origin in (
            "http://localhost:8080", "http://192.0.2.1", "http://127.0.0.1.attacker.test",
            "http://127.0.0.1@192.0.2.1", "http://user@127.0.0.1", "https://127.0.0.1",
            "http://127.0.0.1/ops/v1", "http://127.0.0.1?", "http://127.0.0.1:0",
            "http://[::1%lo0]", "http://127.0.0.1\n",
        ):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                loopback_address(origin)

    def test_bad_token_is_not_echoed(self):
        secret = "secret\nwith-control"
        with patch.dict(os.environ, {"OPS_TOKEN": secret}), contextlib.redirect_stderr(io.StringIO()) as errs:
            self.assertEqual(main([]), 2)
        self.assertNotIn(secret, errs.getvalue())

    def test_unknown_argument_does_not_echo_its_value(self):
        with contextlib.redirect_stderr(io.StringIO()) as errs:
            with self.assertRaises(SystemExit) as exit_status:
                main(["--token", TOKEN])
        self.assertEqual(exit_status.exception.code, 2)
        self.assertNotIn(TOKEN, errs.getvalue())

    def test_hidden_prompt_fails_closed_without_echo_free_input(self):
        def fallback(*_):
            warnings.warn("Insecure getpass fallback", getpass.GetPassWarning)
            self.fail("Token entry must not continue to an echoing prompt")

        with patch.dict(os.environ, {"OPS_TOKEN": ""}), patch("getpass.getpass", side_effect=fallback):
            with contextlib.redirect_stderr(io.StringIO()) as errs:
                self.assertEqual(main([]), 2)
        self.assertIn("Cannot enter a token safely", errs.getvalue())


if __name__ == "__main__":
    unittest.main()
