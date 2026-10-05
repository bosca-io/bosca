#!/usr/bin/env python3
"""
Seed synthetic content-interaction events so the TFRS recommender has training data, then (optionally)
train the model, evaluate the ML_MODEL strategy, and read back the resulting recommendations.

This unblocks the one thing the TF recommendation pipeline can't produce on its own: interaction data.
The two-tower model trains on (user_id, content_id) pairs taken from `warehouse.bosca.events`, so this
script posts `interaction`/`completion` events and qualifying page impressions (the engagement events the
trainer's query selects) for the profiles and content you give it, then flushes them to the warehouse.

Prerequisites
-------------
1. The stack is up (Postgres, Trino, NATS, tf-serving, recommendation-trainer, model-loader) and the
   bosca-server + bosca-runner are running. The server's installer seeds the ML_MODEL strategy.
2. You supply REAL ids:
   * --profiles : real profile ids. recommendations.recommendations.profile_id has a FK to
     public.profiles, so synthetic UUIDs would fail at upsert. Grab a few from Studio or your DB.
   * --content  : real metadata ids, so the served recommendations resolve to actual content. Any feed
     item works (e.g. ids from `feeds { myFeed }`), or any published Metadata.

Example
-------
    python seed_interactions.py \
        --base-url http://localhost:8080 \
        --trainer-url http://localhost:8090 \
        --admin-user admin --admin-pass password \
        --profiles 0e96... 1a2b... 3c4d... \
        --content   aa11... bb22... cc33... dd44... \
        --events-per-profile 40 \
        --train --evaluate --read

Without --train/--evaluate/--read it only seeds + flushes the events (the daily scheduled jobs then
train and evaluate on their own).
"""

import argparse
import json
import random
import time
import urllib.error
import urllib.request


# ---------------------------------------------------------------------------
# HTTP helpers (stdlib only — no external deps)
# ---------------------------------------------------------------------------

def _request(method, url, body=None, headers=None, timeout=120):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        return e.code, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw)


def graphql(base_url, query, variables=None, token=None):
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    status, payload = _request("POST", f"{base_url}/graphql", {"query": query, "variables": variables or {}}, headers)
    if status != 200 or not isinstance(payload, dict) or payload.get("errors"):
        raise RuntimeError(f"GraphQL error ({status}): {json.dumps(payload)[:500]}")
    return payload["data"]


# ---------------------------------------------------------------------------
# Operations (every GraphQL shape + route below is verified against the SDL/source)
# ---------------------------------------------------------------------------

def login(base_url, identifier, password):
    data = graphql(
        base_url,
        """mutation Login($id: String!, $pw: String!) {
             security { login { password(identifier: $id, password: $pw) { token { token } } } }
           }""",
        {"id": identifier, "pw": password},
    )
    return data["security"]["login"]["password"]["token"]["token"]


def discover_ml_strategy_id(base_url, token):
    data = graphql(
        base_url,
        """query { recommendation { strategies { all(offset: 0, limit: 1000) { id type } } } }""",
        token=token,
    )
    for s in data["recommendation"]["strategies"]["all"]:
        if s["type"] == "ML_MODEL":
            return s["id"]
    return None


def device():
    return {
        "installation_id": "seed-interactions",
        "manufacturer": "seed",
        "model": "seed-script",
        "platform": "test",
        "primary_locale": "en-US",
        "system_name": "seed",
        "timezone": "UTC",
        "type": "desktop",
        "version": "1.0",
    }


EVENT_TYPES = ["interaction", "impression", "completion"]


def make_events_payload(profile_id, content_ids, n):
    """One Events envelope: `n` events from `profile_id` against random content ids.

    Mirrors the analytics ingestion contract the trainer reads — context.user_id is the profile,
    and each event's element.content[].id is the metadata the user interacted with.
    """
    now_ms = int(time.time() * 1000)
    events = []
    for i in range(n):
        cid = random.choice(content_ids)
        event_type = random.choice(EVENT_TYPES)
        events.append({
            "created": now_ms - i * 1000,
            "created_micros": 0,
            "type": event_type,
            "element": {
                "id": f"feed-item-{cid}",
                "type": "page" if event_type == "impression" else "metadata",
                "content": [{"id": cid, "type": "metadata", "index": 0, "percent": round(random.uniform(0.2, 1.0), 2)}],
            },
        })
    return {
        "context": {
            "app_id": "seed-interactions",
            "app_version": "1.0.0",
            "device": device(),
            "session_id": f"seed-{profile_id}",
            "user_id": profile_id,
        },
        "events": events,
        "sent": now_ms,
        "sent_micros": 0,
    }


def post_events(events_url, payload):
    status, _ = _request("POST", f"{events_url}/api/v1/events", payload,
                         {"Content-Type": "application/json", "Accept": "application/json"})
    if status not in (200, 202):
        raise RuntimeError(f"event ingest failed: HTTP {status}")


def flush(events_url, token):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    status, _ = _request("GET", f"{events_url}/api/v1/events/flush", None, headers)
    if not (200 <= status < 300):
        raise RuntimeError(f"flush failed: HTTP {status}")


def train(trainer_url, min_interactions, lookback_days):
    status, payload = _request(
        "POST", f"{trainer_url}/train",
        {"min_interactions": min_interactions, "lookback_days": lookback_days},
        {"Content-Type": "application/json"}, timeout=1800,
    )
    print(f"  trainer responded HTTP {status}: {json.dumps(payload)[:300]}")
    return isinstance(payload, dict) and payload.get("status") == "completed"


def evaluate(base_url, token, strategy_id):
    graphql(
        base_url,
        """mutation Eval($id: UUID!) { recommendation { strategies { evaluate(strategyId: $id) { id } } } }""",
        {"id": strategy_id}, token=token,
    )


def read_recs(base_url, token, profile_id, limit=25):
    data = graphql(
        base_url,
        """query Recs($id: UUID!, $limit: Int!) {
             recommendation { profile(profileId: $id, offset: 0, limit: $limit) { metadataId score } }
           }""",
        {"id": profile_id, "limit": limit}, token=token,
    )
    return data["recommendation"]["profile"]


# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="Seed interaction events for the TFRS recommender.")
    ap.add_argument("--base-url", default="http://localhost:8080", help="bosca-server GraphQL base URL")
    ap.add_argument("--events-url", default=None, help="analytics ingestion base URL (defaults to --base-url)")
    ap.add_argument("--trainer-url", default="http://localhost:8090", help="recommendation-trainer base URL")
    ap.add_argument("--admin-user", default="admin")
    ap.add_argument("--admin-pass", default="password")
    ap.add_argument("--profiles", nargs="+", required=True, help="REAL profile ids (FK to public.profiles)")
    ap.add_argument("--content", nargs="+", required=True, help="REAL metadata ids the users 'interact' with")
    ap.add_argument("--events-per-profile", type=int, default=40)
    ap.add_argument("--min-interactions", type=int, default=1, help="trainer threshold override (default 1 for demos)")
    ap.add_argument("--lookback-days", type=int, default=365)
    ap.add_argument("--train", action="store_true", help="trigger model training after seeding")
    ap.add_argument("--evaluate", action="store_true", help="evaluate the ML_MODEL strategy after training")
    ap.add_argument("--read", action="store_true", help="print recommendations per profile at the end")
    args = ap.parse_args()

    events_url = args.events_url or args.base_url
    token = login(args.base_url, args.admin_user, args.admin_pass)
    print(f"Logged in as {args.admin_user}.")

    total = 0
    for pid in args.profiles:
        payload = make_events_payload(pid, args.content, args.events_per_profile)
        post_events(events_url, payload)
        total += len(payload["events"])
    print(f"Posted {total} interaction events for {len(args.profiles)} profiles over {len(args.content)} items.")

    flush(events_url, token)
    print("Flushed events to the warehouse.")

    if args.train:
        print(f"Training (min_interactions={args.min_interactions}, lookback_days={args.lookback_days})...")
        if not train(args.trainer_url, args.min_interactions, args.lookback_days):
            print("  NOTE: training did not report 'completed' — TF Serving may have no model yet; check trainer logs.")

    if args.evaluate:
        sid = discover_ml_strategy_id(args.base_url, token)
        if not sid:
            print("  No ML_MODEL strategy found — is the recommendations package installed (server booted)?")
        else:
            print(f"Evaluating ML_MODEL strategy {sid}...")
            evaluate(args.base_url, token, sid)
            print("  Evaluation complete (recommendations upserted for all profiles).")

    if args.read:
        for pid in args.profiles:
            recs = read_recs(args.base_url, token, pid)
            print(f"  profile {pid}: {len(recs)} recs -> " +
                  ", ".join(f"{r['metadataId']}@{r['score']:.3f}" for r in recs[:5]))

    print("\nDone. If recs are empty: ensure the trainer trained a model (--train) and the model-loader "
          "pushed it to tf-serving, then re-run with --evaluate --read.")


if __name__ == "__main__":
    main()
