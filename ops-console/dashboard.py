"""Keyboard-driven operations dashboard and aggregate-only local reports."""

from contextlib import contextmanager
from datetime import datetime, timezone
import curses
import json
import os
from pathlib import Path
import sys
from urllib.parse import quote

from console import ApiError, Console, COUNTRIES, PROVIDERS, InputError, checked_text


METRICS = (
    ("Accepted", "accepted"), ("Duplicates", "duplicates"),
    ("Unresolved", "unresolved_work"), ("Dead", "dead"), ("Held", "held"),
    ("Oldest pending (s)", "oldest_pending_age_seconds"),
)
TABS = ("Overview", "Notifications", "Detail")
ACCENT, CAUTION, SUCCESS, FAILURE = range(1, 5)


def search_filter(text):
    """Translate an inline query into a single exact, server-authorized filter."""
    text = text.strip()
    if not text:
        return {}
    word, colon, value = text.partition(":")
    if colon and word.casefold() in ("event", "payment", "country", "provider"):
        field = {"event": "event_id", "payment": "payment_key", "country": "country",
                 "provider": "provider"}[word.casefold()]
        if field in ("country", "provider"):
            value = value.strip().upper()
            if value not in (COUNTRIES if field == "country" else PROVIDERS):
                raise InputError(f"Unknown {field}.")
            return {field: value}
        return {field: checked_text(value.strip(), "Search text", 512)}
    upper = text.upper()
    if upper in COUNTRIES:
        return {"country": upper}
    if upper in PROVIDERS:
        return {"provider": upper}
    return {"event_id": checked_text(text, "Event ID", 512)}


def default_report_directory():
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "Meridian Ops" / "reports"
    base = os.environ.get("XDG_DATA_HOME")
    path = Path(base) if base and Path(base).is_absolute() else Path.home() / ".local" / "share"
    return path / "meridian-ops" / "reports"


def save_report(directory, metrics, country, now=None):
    """Save only validated counters, never the API response, search filters, or credentials."""
    if country not in (*COUNTRIES, ""):
        raise InputError("Invalid report country.")
    totals = {}
    for _, key in METRICS:
        value = metrics.get(key)
        if type(value) is not int or value < 0:
            raise InputError("Refresh the overview before saving: a counter is missing or invalid.")
        totals[key] = value
    stamp = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    folder = Path(directory).expanduser().resolve()
    filename = f"overview-{country or 'authorized'}-{stamp:%Y%m%dT%H%M%S%fZ}.json"
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = folder / filename
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump({"scope": country or "authorized", "exported_at": stamp.isoformat(),
                       "metrics": totals}, output, indent=2)
            output.write("\n")
    except OSError:
        path.unlink(missing_ok=True)
        raise
    return path


class Dashboard:
    def __init__(self, api, report_directory=None):
        self.api = api
        self.tab = 0
        self.country = ""
        self.metrics = None
        self.filters = {"limit": "15"}
        self.items = []
        self.cursors = [None]
        self.next_cursor = None
        self.selection = 0
        self.detail = None
        self.target = None
        self.scroll = 0
        self.search = None
        self.status = "Local demo · broker and Salesforce simulated"
        self.report_directory = Path(report_directory) if report_directory else default_report_directory()

    def refresh(self):
        if self.tab == 0:
            self.metrics = None
            self.metrics = self.api.request("/overview", query={"country": self.country} if self.country else None)
        elif self.tab == 1:
            self.items = []
            self.next_cursor = None
            query = self.filters.copy()
            if self.cursors[-1]:
                query["cursor"] = self.cursors[-1]
            result = self.api.request("/notifications", query=query)
            items, next_cursor = result.get("items"), result.get("next_cursor")
            if not isinstance(items, list) or not all(isinstance(item, dict) for item in items):
                raise ApiError("Unexpected notifications page from the local API.")
            self.items = items
            self.next_cursor = next_cursor if isinstance(next_cursor, str) and next_cursor else None
            self.selection = min(self.selection, max(0, len(self.items) - 1))
        elif self.target:
            self.detail = None
            provider, event = self.target
            result = self.api.request(f"/notifications/{provider}/{quote(event, safe='')}")
            if (not isinstance(result.get("event"), dict)
                    or not all(isinstance(result.get(key), list)
                               and all(isinstance(row, dict) for row in result[key])
                               for key in ("jobs", "timeline"))):
                raise ApiError("Unexpected notification detail from the local API.")
            self.detail = result

    @contextmanager
    def prompt(self):
        curses.def_prog_mode()
        curses.endwin()
        try:
            yield Console(self.api)
        finally:
            curses.reset_prog_mode()
            curses.noecho()
            curses.raw()
            self.screen.keypad(True)
            try:
                curses.curs_set(0)
            except curses.error:
                pass
            self.screen.clearok(True)
            self.screen.refresh()

    def draw(self):
        win = self.screen
        height, width = win.getmaxyx()
        win.erase()

        def line(row, text, attr=0, col=1):
            space = width - col - 1
            if 0 <= row < height and space > 0:
                try:
                    win.addnstr(row, col, text, space, attr)
                except curses.error:
                    pass

        if height < 10 or width < 42:
            line(1, "Resize to at least 42 × 10. q quits.")
            win.refresh()
            return
        line(0, " MERIDIAN / PAYMENT OPERATIONS", curses.A_BOLD | self.color(ACCENT))
        col = 1
        for index, name in enumerate(TABS):
            label = f" {index + 1} {name} "
            line(2, label, (curses.A_REVERSE | curses.A_BOLD | self.color(ACCENT))
                 if self.tab == index else curses.A_DIM, col)
            col += len(label) + 1
        footer = (
            "/ Search  c Scope  s Save  r Refresh  ? Help  q Quit",
            "/ Search  ↑↓ Select  Enter Open  n/p Page  f Filter",
            "/ Search  ↑↓ Scroll  Esc Back  R Replay  r Refresh",
        )
        if self.tab == 0:
            line(4, f"Operational snapshot  /  {self.country or 'Authorized countries'}", curses.A_BOLD)
            if self.metrics is not None:
                for index, (label, field) in enumerate(METRICS):
                    emphasis = self.metric_color(field)
                    line(6 + index // 2, f"{label}: {self.api.safe(self.metrics.get(field))}",
                         emphasis, 1 + (index % 2) * (width // 2))
                if height >= 13:
                    line(10, "Last update: " + self.api.safe(self.metrics.get("last_update_at")), curses.A_DIM)
            else:
                line(6, "Snapshot unavailable. Press r to retry.")
        elif self.tab == 1:
            scope = " · ".join(f"{key}={self.api.safe(value)}" for key, value in self.filters.items())
            line(4, f"Page {len(self.cursors)}  /  {scope}", curses.A_BOLD)
            visible = max(1, height - 9)
            first = max(0, self.selection - visible + 1)
            if not self.items:
                line(6, "No notifications on this page. Use f to change filters.")
            for index, item in enumerate(self.items[first:first + visible], first):
                label = (f"{self.api.safe(item.get('country')):3} "
                         f"{self.api.safe(item.get('provider')):9} "
                         f"{self.api.safe(item.get('status')):9} {self.api.safe(item.get('event_id'))}")
                emphasis = self.state_color(item.get("status"))
                if index == self.selection:
                    emphasis |= curses.A_REVERSE | curses.A_BOLD
                line(6 + index - first, label, emphasis)
        else:
            rows = self.detail_lines()
            max_offset = max(0, len(rows) - (height - 7))
            self.scroll = min(self.scroll, max_offset)
            for index, (text, attr) in enumerate(rows[self.scroll:self.scroll + height - 7]):
                line(4 + index, text, attr)
        if self.search is not None:
            prefix = "/ "
            entered = self.api.safe(self.search)
            line(height - 2, prefix + entered[-max(1, width - len(prefix) - 3):],
                 curses.A_BOLD | self.color(ACCENT))
            line(height - 1, "Enter Search  Esc Cancel  Try: CO, ANDINA, event:ID, payment:KEY",
                 curses.A_REVERSE)
        else:
            line(height - 2, self.api.safe(self.status), self.color(ACCENT))
            line(height - 1, footer[self.tab], curses.A_REVERSE)
        win.refresh()

    def detail_lines(self):
        if self.detail is None:
            return [("Select a notification and press Enter.", 0)]
        event = self.detail.get("event") or {}
        safe = self.api.safe
        rows = [(f"{safe(event.get('provider'))} / {safe(event.get('status'))}",
                 curses.A_BOLD | self.state_color(event.get("status"))),
                (f"Event: {safe(event.get('event_id'))}", 0),
                (f"Payment: {safe(event.get('payment_key'))}", 0),
                ("", 0), ("DELIVERY JOBS", curses.A_BOLD)]
        for job in self.detail.get("jobs") or []:
            rows.append((f"{safe(job.get('destination'))}  {safe(job.get('state'))}"
                         f"  |  attempts: {safe(job.get('attempts'))}  |  {safe(job.get('outcome_code'))}",
                         self.state_color(job.get("state"))))
        rows.extend([("", 0), ("TIMELINE / event time → processing time", curses.A_BOLD)])
        for item in self.detail.get("timeline") or []:
            rows.append((safe(item.get("type")), curses.A_BOLD))
            rows.append((f"  {safe(item.get('event_time'))} → {safe(item.get('processing_time'))}", 0))
        return rows

    def switch(self, tab):
        self.tab = tab
        self.scroll = 0
        self.refresh()

    def handle(self, key):
        if self.search is not None:
            self.search_key(key)
        elif key == "/":
            self.search = ""
        elif key in ("1", "2", "3", "\t"):
            self.switch((self.tab + 1) % len(TABS) if key == "\t" else int(key) - 1)
        elif key == "r":
            self.refresh()
            self.status = "Refreshed."
        elif key == "?":
            self.status = "/ search · 1–3 or Tab: switch panels · j/k: move · q quit"
        elif key == "\x1b" and self.tab == 2:
            self.switch(1)
        elif key in (curses.KEY_UP, curses.KEY_DOWN, "j", "k"):
            step = -1 if key in (curses.KEY_UP, "k") else 1
            if self.tab == 1:
                self.selection = max(0, min(len(self.items) - 1, self.selection + step))
            elif self.tab == 2:
                self.scroll = max(0, self.scroll + step)
        elif key in ("\n", "\r", curses.KEY_ENTER) and self.tab == 1 and self.items:
            item = self.items[self.selection]
            provider, event = item.get("provider"), item.get("event_id")
            if provider not in PROVIDERS or not isinstance(event, str):
                raise ApiError("This notification has no usable provider or event ID.")
            self.target = provider, checked_text(event, "Event ID", 512)
            self.switch(2)
        elif key == "n" and self.tab == 1 and self.next_cursor:
            self.cursors.append(self.next_cursor)
            self.selection = 0
            self.refresh()
        elif key == "p" and self.tab == 1 and len(self.cursors) > 1:
            self.cursors.pop()
            self.selection = 0
            self.refresh()
        elif key == "c" and self.tab == 0:
            with self.prompt() as form:
                self.country = form.choice("Country", COUNTRIES, optional=True)
            self.refresh()
        elif key == "f" and self.tab == 1:
            with self.prompt() as form:
                country = form.choice("Country", COUNTRIES, optional=True)
                provider = form.choice("Provider", PROVIDERS, optional=True)
                event = checked_text(input("Event ID (optional): "), "Event ID", 512, optional=True)
                payment = checked_text(input("Payment key (optional): "), "Payment key", 512, optional=True)
            self.filters = {"limit": "15", **{k: v for k, v in (
                ("country", country), ("provider", provider), ("event_id", event),
                ("payment_key", payment),
            ) if v}}
            self.cursors, self.selection = [None], 0
            self.refresh()
        elif key == "s" and self.tab == 0 and self.metrics is not None:
            with self.prompt():
                selected = checked_text(input(f"Report folder [{self.api.safe(str(self.report_directory))}]: ").strip(),
                                        "Report folder", 1024, optional=True)
                folder = Path(selected).expanduser() if selected else self.report_directory
                print("Saves only aggregate counters. No identifiers or credentials.")
                confirmed = input("Type SAVE to confirm: ") == "SAVE"
            if confirmed:
                self.status = f"Saved: {save_report(folder, self.metrics, self.country)}"
                self.report_directory = folder
            else:
                self.status = "Export canceled."
        elif key == "R" and self.tab == 2 and self.detail is not None:
            with self.prompt() as form:
                self.status = form.replay(self.target)
                input("Press Enter to return to the dashboard: ")
            self.refresh()

    def search_key(self, key):
        if key == "\x1b":
            self.search = None
        elif key in ("\n", "\r", curses.KEY_ENTER):
            filters = search_filter(self.search)
            self.search = None
            self.filters = {"limit": "15", **filters}
            self.cursors, self.selection = [None], 0
            self.switch(1)
            self.status = "Search results · / to search again" if filters else "Search cleared."
        elif key in (curses.KEY_BACKSPACE, "\b", "\x7f"):
            self.search = self.search[:-1]
        elif isinstance(key, str) and key.isprintable() and len(self.search) < 521:
            self.search += key

    def color(self, pair):
        return curses.color_pair(pair) if getattr(self, "colors", False) else 0

    def state_color(self, state):
        if state in ("CAPTURED", "PUBLISHED", "APPLIED"):
            return self.color(SUCCESS)
        if state in ("PENDING", "CLAIMED", "HELD"):
            return self.color(CAUTION)
        if state in ("FAILED", "DEAD"):
            return self.color(FAILURE)
        return 0

    def metric_color(self, field):
        count = self.metrics.get(field)
        if field in ("dead", "held", "unresolved_work") and count:
            return self.color(FAILURE if field == "dead" else CAUTION)
        return self.color(SUCCESS) if field == "accepted" and count else 0

    def run(self, screen):
        self.screen = screen
        curses.raw()
        curses.set_escdelay(35)
        try:
            curses.curs_set(0)
        except curses.error:
            pass
        self.colors = False
        if curses.has_colors() and "NO_COLOR" not in os.environ:
            curses.start_color()
            try:
                curses.use_default_colors()
                for pair, color in ((ACCENT, curses.COLOR_CYAN), (CAUTION, curses.COLOR_YELLOW),
                                    (SUCCESS, curses.COLOR_GREEN), (FAILURE, curses.COLOR_RED)):
                    curses.init_pair(pair, color, -1)
                self.colors = True
            except curses.error:
                pass
        try:
            self.refresh()
        except ApiError as error:
            self.status = str(error)
        while True:
            self.draw()
            key = screen.get_wch()
            if key == "\x03" or (key == "q" and self.search is None):
                return
            try:
                self.handle(key)
            except (ApiError, InputError) as error:
                self.status = str(error)
            except OSError:
                self.status = "Could not save the report. Check the destination and try again."


def launch(api, report_directory=None):
    curses.wrapper(Dashboard(api, report_directory).run)
