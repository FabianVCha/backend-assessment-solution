# Local operations console

Use this Python 3 terminal dashboard to inspect accepted notifications and requeue an individual delivery through the [Operations API v1](../docs/api/operations-v1.md). It uses standard-library `curses` on a supported terminal and a plain-menu fallback, with no packages to install. Keyboard-first navigation and summary panels are inspired by [Mole](https://github.com/tw93/Mole); no Mole code or dependency is used.

## Start

From the repository root, with the operations-enabled intake running locally:

```sh
./meridian
```

The [local demo setup](../LOCAL_DEMO.md) enables one-command read-only access. With a link on your `PATH`, run `meridian` from any directory. Otherwise, paste your short-lived bearer token into the hidden prompt or supply `OPS_TOKEN`. The console does not take tokens as arguments, print them, or save them. Leave tokens out of shell history, screenshots, and replay reasons.

The default origin is `http://127.0.0.1:8080`; change the port with `--base-url http://127.0.0.1:8081`. Only literal loopback HTTP origins are accepted, including `http://[::1]:8080`. Proxies and redirects are not used. Run only against a trusted local service.

## Dashboard controls

| Key | Action |
|---|---|
| `1`–`3`, `Tab` | Switch among Overview, Notifications and Detail |
| `/`, `Enter`, `Esc` | Open inline search from any panel, submit, or cancel |
| `↑`/`↓`, `j`/`k`, `Enter` | Select a notification and open it, or scroll its detail |
| `c`, `f`, `n`/`p` | Change overview scope, filter events, or page next/previous |
| `r`, `Esc`, `?`, `q` | Refresh, go back from detail, get keyboard help, or quit |
| `R` in Detail | Preview a replay for the selected event; type `REPLAY` to submit |
| `s` in Overview | Preview the report folder; press Enter to accept the suggested folder or enter another path, then type `SAVE` |

Saved JSON reports contain only the scope, export time and aggregate counters. No event IDs, payment keys, payloads, tokens or replay details are saved. There is no automatic export. Report files are created privately (`0600`) without overwriting. The default is `~/Library/Application Support/Meridian Ops/reports` on macOS and `${XDG_DATA_HOME:-$HOME/.local/share}/meridian-ops/reports` otherwise. Use `--export-dir` to select a different default or change it during export. The selection lasts for the current session only. Keep reports in a trusted folder outside the repository.

Search is exact and respects API permissions. Try `CO`, `ANDINA`, `event:<id>`, or `payment:<key>`. An unprefixed ID searches event IDs; use `f` to combine filters. Submit an empty search to clear the filters. Cyan marks navigation, green indicates completed or captured work, yellow indicates pending or held work, and red indicates failed or dead work. Text labels remain visible. Set `NO_COLOR=1` to disable the palette.

## Real API smoke check

Follow the [local demo setup](../LOCAL_DEMO.md) to start the operations-enabled intake and seed the fictional notifications. From the repo root:

```sh
set -a; . ./.meridian.local.env; set +a
OPS_TOKEN="$(python3 tools/local_demo.py token --country CO --country MX --country CL --replay)" \
  ./meridian --plain
```

The regression walkthrough below uses plain mode for predictable prompts. Omit `--plain` to use the full-screen dashboard in a compatible terminal.

The Andina fixture's Event ID is `4f680840fa2680d69cf3fa35a21faf273b196cbf8743075e2451726aabd87387` (derived from transaction ID and state, not the webhook's notification ID).

1. Choose **1**, then `CO`. Expect `accepted` to include the Andina fixture.
2. Choose **2**, leave all filters blank, and enter `1` at Page size. Expect one item and a next-page prompt. Enter `y` and check that page two has a different event. If there is another page, press Enter to return to the menu. Confirm an exact filter by searching again with the Andina Event ID above.
3. Choose **3** and enter `ANDINA` and the fixture's Event ID. Verify the delivery jobs and `RECEIVED` timeline entry. A missing processing time is shown as `-`. The timeline is a snapshot, not an audit history.
4. Prepare the [disposable failure fixture](#disposable-failure-fixture) below. Choose **4**, enter `ANDINA`, the fixture's Event ID, `CANONICAL_EVENT`, and `Local smoke check` as the reason. Press Enter to generate a key. At the preview, press Enter to cancel. Repeat with the displayed key and type `REPLAY` exactly. Expect `REQUEUED` once the reset commits. Repeat with the **same** target, destination, reason and key; expect the original outcome, without a second reset.
5. Quit with `q` and rerun the command with `token --country CO` (no `--replay`). **1** with `CO` should work, **1** with `MX` should give `403`, and a confirmed **4** should give `403`. A replay-only token, if issued separately, should be denied reads but can replay a known eligible target. A well-formed but invalid token should give `401`.

If a replay fails or the connection drops, the console does not automatically retry. Select **4** again with the same details and key; a new key can trigger a second reset if the first request committed.

### Disposable failure fixture

The normal demo seed does not create failed jobs. On an isolated, disposable `payments-review` demo database only, wait for **3** to show the Andina canonical job as `PUBLISHED`, then inject one synthetic failure in another terminal. Do not run this against a database whose data you want to keep.

```sh
docker compose --env-file .meridian.local.env -p payments-review -f compose.yaml -f compose.ops.yaml \
  exec -T database psql -v ON_ERROR_STOP=1 -U intake -d intake <<'SQL'
UPDATE outbox_job AS job
SET state = 'DEAD', attempts = 1, published_at = NULL
FROM payment_event AS event
WHERE job.intake_id = event.intake_id
  AND event.provider = 'ANDINA'
  AND event.event_id = '4f680840fa2680d69cf3fa35a21faf273b196cbf8743075e2451726aabd87387'
  AND job.destination = 'CANONICAL_EVENT' AND job.state = 'PUBLISHED';
SQL
```

Expect `UPDATE 1`; **3** should now show a `DEAD` milestone. Return to step 4. The simulated relay may republish the job quickly after replay.

## Tests

```sh
python3 -B -m unittest discover -s ops-console -v
```

The tests use a fake API bound to a randomly assigned loopback port. They do not require the intake service, PostgreSQL, or a real token.
