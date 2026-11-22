# Local payment operations demonstration

Run the assessment service on loopback with real PostgreSQL and fictional fixtures. The optional
terminal console consumes the same authenticated operations API introduced in PR #13. It is an
operator client, not a second backend, and it does not require Node, a browser, or cloud accounts.

## Start and inspect

From the repository root, with Docker Compose, OpenSSL and Python 3:

```sh
if [ ! -e .meridian.local.env ]; then
  (umask 077; printf 'INTAKE_OPS_HS256_KEY_BASE64=%s\n' "$(openssl rand -base64 32)" > .meridian.local.env)
fi
docker compose --env-file .meridian.local.env -p payments-review -f compose.yaml -f compose.ops.yaml up -d --build --wait
python3 tools/local_demo.py seed
python3 tools/local_demo.py seed
./meridian
```

For subsequent launches, run only `./meridian`. To make the command available from any directory,
create a link from a directory already on your `PATH`: `mkdir -p ~/.local/bin; ln -s "$PWD/meridian" ~/.local/bin/meridian`.
Do not overwrite another command of the same name. The launcher reads the ignored, private local
profile alongside itself and issues a fresh **read-only** session; it does not start containers or
save a bearer token. Without that profile it uses the normal hidden token prompt.

See the [terminal dashboard instructions](ops-console/README.md) for keyboard navigation and where
aggregate reports are saved. Pass `--plain` for the original prompt-driven menu. The first seed
run should accept one notification per provider in an empty database. A second run should display
duplicates without creating additional jobs. The relay polls approximately every ten seconds; use
the timeline after the first tick to distinguish acceptance, the simulated canonical publication,
and the simulated Salesforce result. This is a snapshot of milestones, not immutable attempt history.

To populate the local operations table with a larger deterministic dataset, send exactly 200
fictional notifications through the signed webhook endpoints:

```sh
python3 tools/local_demo.py seed --count 200
```

The generated records are distributed across Quetzal, Andina and Austral, use unique synthetic
identifiers, and are accepted through the same public webhook path as the three reference samples.
Running the command again reports duplicates rather than creating additional records. If the demo
uses a non-default host port, pass it explicitly, for example
`python3 tools/local_demo.py seed --port 18080 --count 200`.

To issue a narrower or replay-enabled session, first export the values from your trusted local
profile (`set -a; . ./.meridian.local.env; set +a`), then supply `OPS_TOKEN` using
`python3 tools/local_demo.py token --country CO` or add `--replay` for the operator workflow. Use the integration
test fixture for a terminal failure: the normal demo deliberately does not fabricate failed payment
rows. An ordinary replay requires a reason and a reusable idempotency key, and does not reopen a held
or successfully applied job. The token issuer is a **local fixture helper only**. Never put its key
or tokens in Git, a ZIP, logs or the terminal transcript. Tokens expire after 15 minutes.

Optional: set `INTAKE_LOCAL_PORT` and `INTAKE_DB_LOCAL_PORT` to use free host ports when a previous demo
is already running. The seed helper accepts `--port`. Configure the console's loopback URL to match.

```sh
docker compose --env-file .meridian.local.env -p payments-review -f compose.yaml -f compose.ops.yaml logs -f intake
```

In OrbStack, use the `payments-review` project to inspect the intake and database containers. Opening a
webhook URL in a browser is not a supported demo: a browser sends GET and providers send signed POST.

## Verify and clean up

Run the full Java suite with **a disposable database**, not the demonstration database you want
to preserve. Tests clear their tables. See [practical setup](practical/README.md); set
`INTAKE_IT_JDBC_URL`, `INTAKE_IT_DB_USER` and `INTAKE_IT_DB_PASSWORD`, and confirm integration tests
actually ran. The console uses the Python standard-library test runner documented alongside it.

```sh
docker compose --env-file .meridian.local.env -p payments-review -f compose.yaml -f compose.ops.yaml down
unset OPS_TOKEN INTAKE_OPS_HS256_KEY_BASE64
```

By default `down` preserves the named local database volume. Add `--volumes` only if you intend to
discard the demo project's synthetic data.

## What this does not prove

The demo proves local contracts, authorization and orchestration with fictional data. It does not
publish to real Service Bus, write to Salesforce, exercise cloud identities, or prove capacity at
the scenario peak. For Azure rollout, follow the architecture and infrastructure prerequisites;
do not use this Compose configuration as a deployable production manifest.
