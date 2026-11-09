#!/usr/bin/env python3
"""Read-only business checks against the guarded, already-seeded local admin workspace.

Issues temporary local sessions and display metadata only. Never seeds/resets business
records, changes custody, issues PINs or invokes payment/refund/payout operations.
Run once with --evidence, restart the same local API, then run with --compare.
"""
import argparse
import datetime
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "http://127.0.0.1:55840"
ORIGIN = "http://127.0.0.1:5173"
ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
PREFIX = "local-ops-v1-"


def valid(value, prefix):
    if not isinstance(value, str) or len(value) != 11 or value[3] != "-" or value[7] != "-":
        return False
    symbols = value.replace("-", "")
    if symbols[0] != prefix or any(c not in ALPHABET for c in symbols):
        return False
    # Independent polynomial long division over the complete 45-bit codeword.
    word = 0
    for c in symbols:
        word = word * 32 + ALPHABET.index(c)
    while word.bit_length() > 10:
        word ^= 0x633 << (word.bit_length() - 11)
    return word == 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--compare", type=Path)
    args = parser.parse_args()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    checks, references, sessions, notes = [], {}, [], []

    def call(method, path, session=None, body=None, origin=None):
        assert path.startswith("/api/") and "://" not in path and "\n" not in path
        headers = {"Accept": "application/json"}
        if session:
            headers["Authorization"] = "Bearer " + session["accessToken"]
        if origin:
            headers["Origin"] = origin
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
        try:
            with opener.open(request, timeout=30) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        try:
            value = json.loads(raw) if raw else None
        except ValueError:
            value = None
        return status, value

    def check(label, condition):
        checks.append({"check": label, "pass": bool(condition)})
        if not condition:
            raise AssertionError(label)

    def get(path, session, label):
        status, data = call("GET", path, session, origin=ORIGIN if path.startswith("/api/admin/local-fixtures") else None)
        check(label, status == 200)
        return data

    def record(kind, row, prefix, id_field="id"):
        check(kind + " checksum", valid(row.get("publicReference"), prefix))
        key = kind + ":" + row[id_field]
        if key in references:
            check(kind + " stable repeated projection", references[key] == row["publicReference"])
        references[key] = row["publicReference"]

    def login(family, key):
        status, capability = call("GET", "/api/auth/development-access?appSource=" + family)
        check("guarded local native capability " + key, status == 200 and capability.get("enabled") and capability.get("mode") == "LOCAL_FIXTURE")
        status, session = call("POST", "/api/auth/development-session", body={"appSource": family,
            "accountKey": key, "deviceId": "reference-check-" + key + "-" + uuid.uuid4().hex})
        check("local session " + key, status == 200 and bool(session.get("accessToken")))
        sessions.append(session)
        me = get("/api/auth/me", session, "authenticated profile " + key)
        check("synthetic account " + key, me["id"].startswith(PREFIX))
        record("USER", me, "A")
        return session

    try:
        status, admin = call("POST", "/api/admin-auth/development-session", body={
            "deviceId": "reference-check-admin-" + uuid.uuid4().hex, "deviceLabel": "Local reference read checks"}, origin=ORIGIN)
        check("guarded local admin session", status == 200 and bool(admin.get("accessToken")))
        sessions.append(admin)
        manifest = get("/api/admin/local-fixtures", admin, "installed fixture manifest")
        check("fixed synthetic workspace", manifest.get("environment") == "LOCAL_FIXTURE" and manifest.get("namespace") == "local-ops-v1" and bool(manifest.get("seededAt")))
        check("fixture accounts use references", all(valid(a.get("publicReference"), "A") for a in manifest["accounts"]))
        check("fixture scenarios retain canonical IDs and add references", all(s.get("resourceIds") and s.get("resourceReferences") for s in manifest["scenarios"]))
        admin_orders = get("/api/admin/web/orders?limit=100", admin, "admin order reads")
        for row in admin_orders:
            record("ORDER", row, "C")
        for path, kind, prefix in [("users", "USER", "A"), ("merchants", "MERCHANT", "V"), ("returns", "RETURN", "R")]:
            for row in get("/api/admin/web/" + path + "?limit=100", admin, "admin " + path):
                record(kind, row, prefix)
        deliveries = get("/api/admin/web/deliveries?limit=100", admin, "admin deliveries")
        for row in deliveries["missions"]:
            record("DELIVERY", row, "D")
            check("delivery order reference agrees", row.get("orderReference") == references["ORDER:" + row["orderId"]])

        ama, koffi = login("SEQUO_APP", "ama"), login("SEQUO_APP", "koffi")
        owned = get("/api/orders", ama, "customer persisted orders")
        check("customer has fixture orders", bool(owned))
        for row in owned:
            order = row.get("order", row)
            record("ORDER", order, "C")
        target = owned[0].get("order", owned[0])
        status, _ = call("GET", "/api/orders/" + urllib.parse.quote(target["id"], safe=""), koffi)
        check("other customer cannot read order", status in (403, 404))
        for key, store in [("yawa", "maison"), ("mawuli", "atelier")]:
            vendor = login("SEQUO_VENDOR", key)
            parts = get("/api/merchant/sub-orders?merchantId=" + PREFIX + "merchant-" + store + "&history=true", vendor, "merchant scoped history " + key)
            for part in parts:
                record("MERCHANT_ORDER", part, "M")
                check("merchant parent agrees", part.get("orderReference") == references["ORDER:" + part["orderId"]])
            other = "atelier" if store == "maison" else "maison"
            status, _ = call("GET", "/api/merchant/sub-orders?merchantId=" + PREFIX + "merchant-" + other, vendor)
            check("other merchant queue denied " + key, status == 403)
        rider = login("SEQUO_RIDER", "senam")
        for mission in get("/api/rider/missions", rider, "rider mission read"):
            record("DELIVERY", mission, "D")

        for key, site, other in [("dede", "tokoin", "adidogome"), ("esso", "adidogome", "tokoin")]:
            hub = login("SEQUO_HUB", key)
            point, other_point = PREFIX + "relay-" + site, PREFIX + "relay-" + other
            for parcel in get("/api/relay/parcels?relayPointId=" + point, hub, "scoped relay parcels " + key):
                record("PARCEL", parcel, "P")
            returns = get("/api/returns/relay?relayPointId=" + point, hub, "scoped relay returns " + key)
            body = {"kind": "RETURN", "reference": "R98-765-4Q4", "relayPointId": point}
            if returns:
                returned = returns[0]
                record("RETURN", returned, "R")
                check("relay customer identity redacted", returned.get("customerId") == "" and not returned.get("customerReference"))
                body["reference"] = returned["publicReference"].lower().replace("-", " ")
                status, result = call("POST", "/api/references/resolve", hub, body)
                check("normalized return lookup " + key, status == 200 and result == {"kind": "RETURN", "id": returned["id"], "publicReference": returned["publicReference"]})
                status, result = call("POST", "/api/references/resolve", hub, dict(body, reference=returned["id"], legacyId=True))
                check("explicit older return lookup " + key, status == 200 and result["id"] == returned["id"])
            else:
                notes.append(key + ": no assigned return fixture; own parcel references and cross-site return denial checked instead.")
            status, _ = call("POST", "/api/references/resolve", hub, dict(body, relayPointId=other_point))
            check("forged relay denied " + key, status == 403)
            wrong_site_return = next((r for r in get("/api/admin/web/returns?limit=100", admin, "cross site fixture source") if r.get("relayPointId") == other_point), None)
            if wrong_site_return:
                status, _ = call("POST", "/api/references/resolve", hub, dict(body, reference=wrong_site_return["publicReference"]))
                check("other site return unavailable " + key, status == 404)
        body = {"kind": "ORDER", "reference": target["publicReference"]}
        status, result = call("POST", "/api/references/resolve", admin, body)
        check("admin exact short lookup retains ID", status == 200 and result["id"] == target["id"])
        typo = body["reference"][:-1] + ("0" if body["reference"][-1] != "0" else "1")
        status, _ = call("POST", "/api/references/resolve", admin, dict(body, reference=typo))
        check("checksum typo rejected", status == 400)
        status, _ = call("POST", "/api/references/resolve", ama, body)
        check("customer cannot use operator resolver", status == 403)
        status, _ = call("POST", "/api/references/resolve", None, body)
        check("missing session fails closed", status in (401, 403))
        for row in get("/api/admin/web/orders?limit=100", admin, "repeat admin read"):
            record("ORDER", row, "C")
        if args.compare:
            before = json.loads(args.compare.read_text())["references"]
            check("all earlier references survive API restart", bool(before) and all(references.get(k) == v for k, v in before.items()))
    finally:
        for session in reversed(sessions):
            try:
                call("POST", "/api/auth/logout", body={"refreshToken": session["refreshToken"]})
            except Exception:
                pass
        evidence = {"environment": "LOCAL_FIXTURE", "checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "businessWrites": False, "providerOperations": False, "checks": checks, "references": references, "notes": notes}
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(evidence, indent=2) + "\n")
        print(f"{sum(c['pass'] for c in checks)}/{len(checks)} local reference checks passed; evidence: {args.evidence}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
