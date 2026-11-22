#!/usr/bin/env python3
"""Sign fictional exercises and issue short-lived tokens for the loopback demo only."""

import argparse
import base64
from collections import Counter
import copy
import datetime
import hmac
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PROVIDERS = (
    ("MX", "quetzal", "01-charge-succeeded.json", "QUETZAL_WEBHOOK_SECRET",
     "qz_test_whsec_4f8a1c2e9b7d"),  # gitleaks:allow
    ("CO", "andina", "02-transaccion-aprobada.json", "ANDINA_WEBHOOK_SECRET",
     "andina_test_secret_7c3e19ab"),  # gitleaks:allow
    ("CL", "austral", "01-payment-confirmed.json", "AUSTRAL_WEBHOOK_SECRET",
     "aus_test_whsec_2b9d6f10"),  # gitleaks:allow
)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=")


def jwt(countries, replay):
    key = base64.b64decode(os.environ.get("INTAKE_OPS_HS256_KEY_BASE64", ""), validate=True)
    if len(key) < 32:
        raise ValueError("Set the local operations key to at least 32 bytes encoded in Base64.")
    roles = ["read", "replay"] if replay else ["read"]
    claims = {"iss": os.environ.get("INTAKE_OPS_ISSUER", "meridian-local"),
              "aud": os.environ.get("INTAKE_OPS_AUDIENCE", "intake-ops"),
              "exp": int(time.time()) + 900, "sub": "local-demo-operator",
              "roles": roles, "countries": countries}
    signing = b".".join((b64url(b'{"alg":"HS256","typ":"JWT"}'),
                         b64url(json.dumps(claims, separators=(",", ":")).encode())))
    return (signing + b"." + b64url(hmac.digest(key, signing, "sha256"))).decode()


def signature(country, key, data):
    if country == "MX":
        stamp = str(int(time.time()))
        digest = hmac.digest(key, stamp.encode() + b"." + data, "sha256").hex()
        return {"Quetzal-Signature": f"t={stamp},v1={digest}"}
    if country == "CO":
        stamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
        digest = hmac.digest(key, stamp.encode() + b"." + data, "sha256")
        return {"X-Andina-Timestamp": stamp, "X-Andina-Signature": base64.b64encode(digest).decode()}
    return {"X-Austral-Signature": hmac.digest(key, data, "sha256").hex()}


def _sample(provider, country, name):
    return json.loads(
        (ROOT / "reference" / "providers" / f"{provider}-{country.lower()}"
         / "samples" / name).read_text(encoding="utf-8")
    )


def _bulk_payload(provider, payload, index):
    suffix = f"{index:04d}"
    policy_number = f"{900000000 + index:09d}"
    if provider == "quetzal":
        charge = payload["data"]["charge"]
        payload["id"] = f"evt_demo_{suffix}"
        charge["id"] = f"ch_demo_{suffix}"
        charge["reference"] = f"POL-MX-{policy_number}"
        charge["paid_at"] = 1795888796 + index
        charge["customer"]["email"] = f"demo-mx-{suffix}@example.com"
    elif provider == "andina":
        stamp = (
            datetime.datetime(2026, 11, 28, 14, 3, 11,
                              tzinfo=datetime.timezone(datetime.timedelta(hours=-5)))
            + datetime.timedelta(minutes=index)
        )
        transaction = payload["transaccion"]
        payload["notificacion_id"] = f"ntf-demo-{suffix}"
        payload["fecha_notificacion"] = stamp.isoformat()
        transaction["id"] = f"TRX-CO-DEMO-{suffix}"
        transaction["referencia"] = f"POL-CO-{policy_number}"
        transaction["fecha_transaccion"] = (stamp - datetime.timedelta(seconds=2)).isoformat()
        transaction["pagador"]["nombre"] = f"Demo Customer {suffix}"
        transaction["pagador"]["documento"] = f"CC DEMO {suffix}"
        transaction["pagador"]["correo"] = f"demo-co-{suffix}@example.com"
    else:
        stamp = datetime.datetime(2026, 11, 28, 17, 5, 44,
                                  tzinfo=datetime.timezone.utc) + datetime.timedelta(minutes=index)
        payment = payload["payment"]
        payload["eventId"] = f"aus_ev_demo_{suffix}"
        payload["sentAt"] = stamp.isoformat(timespec="seconds").replace("+00:00", "Z")
        payment["paymentId"] = f"AUS-P-DEMO-{suffix}"
        payment["policyNumber"] = f"POL-CL-{policy_number}"
        payment["occurredAt"] = (stamp - datetime.timedelta(seconds=4)).isoformat(
            timespec="seconds"
        ).replace("+00:00", "Z")
        payment["payer"]["name"] = f"Demo Customer {suffix}"
        payment["payer"]["rut"] = f"DEMO-{suffix}"
        payment["payer"]["email"] = f"demo-cl-{suffix}@example.com"
    return payload


def bulk_fixtures(count):
    if not isinstance(count, int) or not 1 <= count <= 1000:
        raise ValueError("Bulk seed count must be between 1 and 1000.")
    fixtures = []
    for index in range(count):
        country, provider, name, variable, default = PROVIDERS[index % len(PROVIDERS)]
        payload = _bulk_payload(provider, copy.deepcopy(_sample(provider, country, name)), index + 1)
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        secret = os.environ.get(variable, default).encode()
        fixtures.append((country, provider.upper(), body, secret))
    return fixtures


def _send_fixture(opener, port, country, provider, body, secret):
    headers = signature(country, secret, body)
    headers["Content-Type"] = "application/json"
    url = f"http://127.0.0.1:{port}/webhooks/{country.lower()}/{provider.lower()}"
    with opener.open(urllib.request.Request(url, body, headers), timeout=8) as response:
        if response.status not in (200, 204):
            raise ValueError(f"{provider} returned an unexpected receipt.")
        status = json.loads(response.read()).get("status") if response.status == 200 else "DUPLICATE"
        if status not in ("ACCEPTED", "DUPLICATE"):
            raise ValueError(f"{provider} returned an unexpected status.")
        return response.status, status


def seed(port, count=None):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    if count is None:
        fixtures = []
        for country, provider, name, variable, default in PROVIDERS:
            body = (ROOT / "reference" / "providers" / f"{provider}-{country.lower()}"
                    / "samples" / name).read_bytes()
            fixtures.append((country, provider.upper(), body,
                             os.environ.get(variable, default).encode()))
    else:
        fixtures = bulk_fixtures(count)

    totals = Counter()
    for country, provider, body, secret in fixtures:
        http_status, status = _send_fixture(opener, port, country, provider, body, secret)
        totals[status] += 1
        if count is None:
            print(f"{country} {provider.lower()}: HTTP {http_status} {status}")
    if count is not None:
        print(f"Seeded {count} notifications: {totals['ACCEPTED']} accepted, "
              f"{totals['DUPLICATE']} duplicates.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    issue = sub.add_parser("token", help="capture the result rather than printing it")
    issue.add_argument("--country", action="append", choices=("MX", "CO", "CL"))
    issue.add_argument("--replay", action="store_true", help="add replay access to the demo identity")
    fixture = sub.add_parser("seed", help="send signed assessment samples")
    fixture.add_argument("--port", default=os.environ.get("INTAKE_LOCAL_PORT", "8080"))
    fixture.add_argument("--count", type=int,
                         help="generate exactly N deterministic fictional notifications")
    args = parser.parse_args()
    try:
        if args.command == "token":
            print(jwt(sorted(set(args.country or ["CO"])), args.replay))
        else:
            if not re.fullmatch(r"[1-9][0-9]{0,4}", args.port) or int(args.port) > 65535:
                raise ValueError("Provide a valid loopback TCP port.")
            seed(args.port, args.count)
    except urllib.error.HTTPError as exc:
        print(f"Local fixture request failed: HTTP {exc.code}", file=sys.stderr)
        return 1
    except (OSError, ValueError) as exc:
        print(f"Local demo unavailable: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
