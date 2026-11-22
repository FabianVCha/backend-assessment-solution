#!/usr/bin/env python3
"""Interactive console for the local Operations API."""

import argparse
import getpass
import http.client
import ipaddress
import json
import os
import re
import sys
import unicodedata
import warnings
from urllib.parse import quote, urlencode, urlsplit
from uuid import uuid4


PROVIDERS = ("ANDINA", "QUETZAL", "AUSTRAL")
COUNTRIES = ("CO", "MX", "CL")
DESTINATIONS = ("CANONICAL_EVENT", "SALESFORCE")
EVENT_FIELDS = (
    "provider", "country", "event_id", "payment_key", "status",
    "occurred_at", "status_changed_at", "received_at",
)
OVERVIEW_FIELDS = (
    "accepted", "duplicates", "unresolved_work", "dead", "held",
    "oldest_pending_age_seconds", "last_update_at",
)
MAX_RESPONSE = 2 * 1024 * 1024


def loopback_address(base_url):
    try:
        url = urlsplit(base_url)
        host = ipaddress.ip_address(url.hostname or "")
        port = 80 if url.port is None else url.port
        if (
            url.scheme == "http" and host.is_loopback and 1 <= port <= 65535
            and url.path in ("", "/") and url.username is None
            and "%" not in url.netloc and "?" not in base_url and "#" not in base_url
            and not any(ch.isspace() or not ch.isprintable() for ch in base_url)
        ):
            return str(host), port
    except ValueError:
        pass
    raise ValueError("Use a loopback HTTP origin such as http://127.0.0.1:8080.")


class ApiError(Exception):
    """An operator-facing error; never contains raw HTTP or exception text."""


class InputError(ValueError):
    """A validation error safe to display without echoing input."""


class Api:
    def __init__(self, base_url, token):
        self.host, self.port = loopback_address(base_url)
        if not re.fullmatch(r"[A-Za-z0-9._~+/-]+=*", token):
            raise ValueError("Provide a valid bearer token via OPS_TOKEN or the hidden prompt.")
        self._token = token

    def safe(self, value):
        if value is None:
            return "-"
        if not isinstance(value, (str, int, float, bool)):
            return "[unexpected value]"
        text = str(value).replace(self._token, "[redacted]")
        return "".join(ch if ch.isprintable() else ascii(ch)[1:-1] for ch in text)

    def request(self, path, *, query=None, replay=None):
        target = "/ops/v1" + path
        try:
            if query:
                target += "?" + urlencode(query)
        except UnicodeError:
            raise ApiError("The local API returned an invalid pagination cursor.") from None
        headers = {"Accept": "application/json", "Authorization": "Bearer " + self._token}
        body = None
        if replay is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(replay, ensure_ascii=False).encode("utf-8")
        # A direct connection ignores environment proxies and never follows redirects.
        conn = http.client.HTTPConnection(self.host, self.port, timeout=10)
        try:
            conn.request("POST" if replay is not None else "GET", target, body, headers)
            with conn.getresponse() as response:
                status = response.status
                if status != 200 and status != 409:
                    raise ApiError(http_error(status, replay is not None))
                data = response.read(MAX_RESPONSE + 1)
        except (OSError, http.client.HTTPException):
            message = "Could not complete the request to the local API."
            if replay is not None:
                message += " Replay outcome is unknown."
            raise ApiError(message) from None
        finally:
            conn.close()

        try:
            result = json.loads(data) if len(data) <= MAX_RESPONSE else None
        except (ValueError, RecursionError):
            result = None
        if status == 409:
            error = result.get("error") if isinstance(result, dict) else None
            code = error.get("code") if isinstance(error, dict) else None
            if replay is not None and code == "JOB_CLAIMED":
                raise ApiError("Job is claimed. Wait for it to finish before retrying with the same key.")
            if replay is not None and code == "KEY_TARGET_CONFLICT":
                raise ApiError("This key already belongs to a different target or destination in this country.")
            if replay is not None and code == "RECONCILIATION_REQUIRED":
                raise ApiError("Job is held and requires reconciliation. Replay cannot clear the hold.")
            if replay is not None and code == "JOB_NOT_REPLAYABLE":
                raise ApiError("The job is not eligible for replay in its current state.")
            raise ApiError("Request conflicted with the current state (409).")
        if not isinstance(result, dict):
            message = "Unexpected response from the local API."
            if replay is not None:
                message += " Replay outcome is unknown."
            raise ApiError(message)
        return result


def http_error(status, replay):
    if status == 401:
        return "Authentication failed (401). Restart with a valid, unexpired token."
    if status == 403:
        role = "replay" if replay else "read"
        return f"Access denied (403). Check the token's {role} role and country scope."
    if status == 404:
        return "Not found (404). The target may be absent or outside your country scope."
    if status == 400:
        return "The API rejected the request (400). Check the input."
    if status == 503:
        return "The operations store is unavailable (503). Try again later."
    if 300 <= status < 400:
        return "The local API redirected the request. Redirects are not followed."
    return f"The operations API returned HTTP {status}."


def checked_text(value, label, maximum, optional=False):
    if optional and not value:
        return ""
    if (
        not value.strip() or len(value.encode("utf-16-le", "surrogatepass")) // 2 > maximum
        or any(unicodedata.category(ch) in ("Cc", "Cs") or ch == "\ufffd" for ch in value)
    ):
        raise InputError(f"{label} must be nonblank, at most {maximum} characters, and contain no controls.")
    return value


class Console:
    def __init__(self, api, read=input, write=print):
        self.api = api
        self.read = read
        self.write = write

    def fields(self, item, names, separator=" | "):
        if not isinstance(item, dict):
            raise ApiError("Unexpected record from the local API.")
        return separator.join(f"{name}: {self.api.safe(item.get(name))}" for name in names)

    def choice(self, label, values, optional=False):
        choices = "/".join(values)
        hint = "; Enter for all" if optional else ""
        value = self.read(f"{label} ({choices}{hint}): ").strip().upper()
        if value not in values and not (optional and not value):
            raise InputError(f"Choose one of: {choices}.")
        return value

    def overview(self):
        country = self.choice("Country", COUNTRIES, optional=True)
        result = self.api.request("/overview", query={"country": country} if country else None)
        self.write(self.fields(result, OVERVIEW_FIELDS, separator="\n"))

    def notifications(self):
        provider = self.choice("Provider", PROVIDERS, optional=True)
        country = self.choice("Country", COUNTRIES, optional=True)
        event = checked_text(self.read("Event ID (optional): "), "Event ID", 512, optional=True)
        payment = checked_text(self.read("Payment key (optional): "), "Payment key", 512, optional=True)
        limit = self.read("Page size [25]: ").strip() or "25"
        if not re.fullmatch(r"(?:[1-9][0-9]?|100)", limit):
            raise InputError("Page size must be between 1 and 100.")
        query = {k: v for k, v in (
            ("provider", provider), ("country", country), ("event_id", event),
            ("payment_key", payment), ("limit", limit),
        ) if v}
        page = 1
        while True:
            result = self.api.request("/notifications", query=query)
            items, cursor = result.get("items"), result.get("next_cursor")
            if not isinstance(items, list) or (
                cursor is not None and (not isinstance(cursor, str) or not cursor)
            ):
                raise ApiError("Unexpected notifications page from the local API.")
            self.write(f"Page {page} ({len(items)} notifications)")
            if not items:
                self.write("No notifications match.")
            for item in items:
                self.write(self.fields(item, EVENT_FIELDS))
            if not cursor or self.read("Next page? [y/N]: ").strip().lower() != "y":
                return
            query["cursor"] = cursor
            page += 1

    def target(self):
        provider = self.choice("Provider", PROVIDERS)
        event = checked_text(self.read("Event ID: "), "Event ID", 512)
        return provider, event

    def detail(self):
        provider, event = self.target()
        path = f"/notifications/{provider}/{quote(event, safe='')}"
        result = self.api.request(path)
        self.write(self.fields(result.get("event"), EVENT_FIELDS))
        jobs, timeline = result.get("jobs"), result.get("timeline")
        if (jobs is not None and not isinstance(jobs, list)) or not isinstance(timeline, list):
            raise ApiError("Unexpected notification detail from the local API.")
        self.write("Delivery jobs")
        if not jobs:
            self.write("No delivery jobs.")
        for job in jobs or []:
            self.write(self.fields(job, (
                "destination", "state", "attempts", "next_attempt_at", "processing_time", "outcome_code",
            )))
        self.write("Timeline (current milestones)")
        for moment in timeline:
            self.write(self.fields(moment, ("type", "event_time", "processing_time", "outcome_code")))

    def replay(self, target=None):
        provider, event = target if target is not None else self.target()
        destination = self.choice("Destination", DESTINATIONS)
        reason = checked_text(self.read("Reason (do not include payer data): "), "Reason", 1000)
        key = checked_text(
            self.read("Idempotency key (reuse for retry; Enter to generate): ") or str(uuid4()),
            "Idempotency key", 128,
        )
        payload = dict(provider=provider, event_id=event, destination=destination,
                       reason=reason, idempotency_key=key)
        self.write("Replay request (reconcile HELD jobs first):")
        self.write(self.fields(payload, payload, separator="\n"))
        if self.read("Type REPLAY to confirm; anything else cancels: ") != "REPLAY":
            self.write("Replay canceled.")
            return "Replay canceled."
        try:
            result = self.api.request("/replays", replay=payload)
            if result.get("outcome") != "REQUEUED":
                raise ApiError("Unexpected replay response. Replay outcome is unknown.")
        except ApiError:
            self.write("If you retry, reuse the same key, target, destination, and reason.")
            raise
        self.write("REQUEUED: the replay was accepted; delivery is not confirmed.")
        return "REQUEUED · delivery not confirmed"

    def run(self):
        actions = {"1": self.overview, "2": self.notifications, "3": self.detail, "4": self.replay}
        self.write("Local operations console")
        while True:
            self.write("\n1. Overview\n2. Notifications\n3. Notification detail\n4. Replay\nq. Quit")
            selection = self.read("Choose an action: ").strip().lower()
            if selection == "q":
                return
            if selection not in actions:
                self.write("Choose 1, 2, 3, 4, or q.")
                continue
            try:
                actions[selection]()
            except (ApiError, InputError) as error:
                self.write(str(error))


class ArgumentParser(argparse.ArgumentParser):
    def error(self, _message):
        self.print_usage(sys.stderr)
        self.exit(2, "Invalid arguments. Use --help; do not pass tokens on the command line.\n")


def main(argv=None):
    parser = ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080",
                        help="local HTTP origin (default: %(default)s)")
    parser.add_argument("--plain", action="store_true", help="use the line-oriented menu")
    parser.add_argument("--export-dir", help="default directory for aggregate reports in dashboard mode")
    args = parser.parse_args(argv)
    try:
        loopback_address(args.base_url)
        token = os.environ.get("OPS_TOKEN")
        if not token:
            with warnings.catch_warnings():
                warnings.simplefilter("error", getpass.GetPassWarning)
                token = getpass.getpass("Operations bearer token: ")
        api = Api(args.base_url, token)
        if not args.plain and sys.stdin.isatty() and sys.stdout.isatty() and os.environ.get("TERM") not in (None, "", "dumb"):
            try:
                from dashboard import launch
            except ImportError:
                print("Full-screen terminal support unavailable; using the plain menu.", file=sys.stderr)
            else:
                launch(api, args.export_dir)
                return 0
        Console(api).run()
    except getpass.GetPassWarning:
        print("Cannot enter a token safely here. Use a terminal or supply OPS_TOKEN.", file=sys.stderr)
        return 2
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    except (EOFError, KeyboardInterrupt):
        print("\nConsole closed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
