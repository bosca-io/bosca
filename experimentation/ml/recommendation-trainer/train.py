#!/usr/bin/env python3
"""
Bosca Content Recommendation Model Trainer — entrypoint.

Trains a TensorFlow Recommenders (TFRS) two-tower retrieval model (plus a content-only cold-start
fallback) from data served by Bosca's analytics GraphQL. The training logic lives in the ``trainer``
package; this file is the thin entrypoint: a long-lived HTTP service and a direct CLI ``train`` command
used by Kubernetes Jobs. The service starts named runs in the background (``POST /runs/<id>``) and reports
them (``GET /runs/<id>``), so a caller can poll a long run without holding a request open; the
synchronous ``POST /train`` remains for older callers.

Usage (service mode):
    python train.py serve --port 8090

Usage (CLI mode):
    python train.py train --bosca-url http://bosca-server:8080

The exported model is served via TensorFlow Serving and queried by
Bosca's ML_MODEL recommendation strategy.
"""

import argparse
import json
import logging
import os
import re
import sys
import threading
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional, Tuple

from trainer.pipeline import run_training

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)


# -- HTTP Service --


RUN_ID_PATTERN = re.compile(r"[A-Za-z0-9._-]{1,128}")
RUN_PATH_PATTERN = re.compile(r"/runs/([^/?#]+)")


class TrainerService:
    """Manages training state and prevents concurrent training runs.

    A run started with :meth:`start_run` trains on a background thread under the same lock as the
    synchronous :meth:`start_training`, so at most one run trains at a time. Finished results are kept
    for the most recent :attr:`MAX_FINISHED_RUNS` run IDs so a poller can collect them after the run ends.
    """

    MAX_FINISHED_RUNS = 32

    def __init__(self, default_config: dict):
        self.default_config = default_config
        self.training_lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.last_result: Optional[dict] = None
        self.is_training = False
        self.current_run_id: Optional[str] = None
        self.finished_runs: "OrderedDict[str, dict]" = OrderedDict()

    def _train(self, override_config: Optional[dict]) -> dict:
        """Runs one training pass, converting any failure into a ``failed`` result."""
        try:
            config = {**self.default_config}
            if override_config:
                config.update(override_config)
            return run_training(config)
        except Exception as e:
            log.exception("Training failed")
            return {"status": "failed", "message": str(e)}

    def start_training(self, override_config: Optional[dict] = None) -> dict:
        """Starts a training run. Returns immediately if already training."""
        if not self.training_lock.acquire(blocking=False):
            return {"status": "already_training", "message": "A training run is already in progress."}

        self.is_training = True
        try:
            result = self._train(override_config)
            self.last_result = result
            return result
        finally:
            self.is_training = False
            self.training_lock.release()

    def start_run(self, run_id: str, override_config: Optional[dict] = None) -> Tuple[int, dict]:
        """Starts ``run_id`` on a background thread unless it is already running or finished.

        Repeating a start for the same ID is harmless: it reports the run's current state. A different run
        in progress yields ``already_training`` (HTTP 409) so the caller retries later.
        """
        with self.state_lock:
            if run_id == self.current_run_id:
                return 200, {"status": "running", "run_id": run_id}
            if run_id in self.finished_runs:
                return 200, {"status": "finished", "run_id": run_id, "result": self.finished_runs[run_id]}
            if not self.training_lock.acquire(blocking=False):
                return 409, {
                    "status": "already_training",
                    "run_id": self.current_run_id,
                    "message": "A training run is already in progress.",
                }
            self.current_run_id = run_id
            self.is_training = True
        threading.Thread(
            target=self._run,
            args=(run_id, override_config),
            name=f"training-{run_id}",
            daemon=True,
        ).start()
        return 202, {"status": "started", "run_id": run_id}

    def _run(self, run_id: str, override_config: Optional[dict]) -> None:
        result = {"status": "failed", "message": "Training ended unexpectedly"}
        try:
            result = self._train(override_config)
        finally:
            with self.state_lock:
                self.finished_runs[run_id] = result
                while len(self.finished_runs) > self.MAX_FINISHED_RUNS:
                    self.finished_runs.popitem(last=False)
                self.last_result = result
                self.current_run_id = None
                self.is_training = False
                self.training_lock.release()

    def get_run(self, run_id: str) -> Optional[dict]:
        """The state of ``run_id``: running, finished with its result, or ``None`` when unknown."""
        with self.state_lock:
            if run_id == self.current_run_id:
                return {"status": "running", "run_id": run_id}
            if run_id in self.finished_runs:
                return {"status": "finished", "run_id": run_id, "result": self.finished_runs[run_id]}
            return None

    def get_status(self) -> dict:
        return {
            "is_training": self.is_training,
            "current_run_id": self.current_run_id,
            "last_result": self.last_result,
        }


def make_handler(trainer: TrainerService):
    """Creates an HTTP request handler bound to the given trainer."""

    class Handler(BaseHTTPRequestHandler):
        def _read_config(self) -> dict:
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length) if content_length > 0 else b"{}"
            try:
                override_config = json.loads(body) if body.strip() else {}
            except json.JSONDecodeError:
                override_config = {}
            return override_config if isinstance(override_config, dict) else {}

        def _run_id(self) -> Optional[str]:
            match = RUN_PATH_PATTERN.fullmatch(self.path)
            if match is None:
                return None
            return match.group(1)

        def do_POST(self):
            if self.path == "/train":
                result = trainer.start_training(self._read_config())
                self._send_json(200, result)
                return
            run_id = self._run_id()
            if run_id is None:
                self._send_json(404, {"error": "Not found"})
            elif not RUN_ID_PATTERN.fullmatch(run_id):
                self._send_json(400, {"error": "Invalid run id"})
            else:
                code, result = trainer.start_run(run_id, self._read_config())
                self._send_json(code, result)

        def do_GET(self):
            if self.path == "/health":
                self._send_json(200, {"status": "ok"})
                return
            if self.path == "/status":
                self._send_json(200, trainer.get_status())
                return
            run_id = self._run_id()
            if run_id is None or not RUN_ID_PATTERN.fullmatch(run_id):
                self._send_json(404, {"error": "Not found"})
                return
            run = trainer.get_run(run_id)
            if run is None:
                self._send_json(404, {"error": "Unknown run", "run_id": run_id})
            else:
                self._send_json(200, run)

        def _send_json(self, code: int, data: dict):
            body = json.dumps(data).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            log.info("%s %s", self.address_string(), format % args)

    return Handler


def run_server(port: int, default_config: dict):
    """Runs the trainer as a long-lived HTTP service."""
    trainer = TrainerService(default_config)
    handler = make_handler(trainer)
    # Threaded so /health, /status and run polling answer while a run trains on another thread.
    server = ThreadingHTTPServer(("0.0.0.0", port), handler)
    server.daemon_threads = True
    log.info("Trainer service listening on port %d", port)
    log.info("  POST /runs/<id> - start a named run in the background (optional JSON config overrides)")
    log.info("  GET  /runs/<id> - run state: running, or finished with its result")
    log.info("  POST /train     - train synchronously (accepts optional JSON config overrides)")
    log.info("  GET  /health    - health check")
    log.info("  GET  /status    - training status and last result")
    server.serve_forever()


# -- Main --

def parse_configuration_json(value: Optional[str]) -> dict:
    """Parses one per-run JSON override object supplied by the Bosca dispatch job."""
    if not value:
        return {}
    configuration = json.loads(value)
    if not isinstance(configuration, dict):
        raise ValueError("--configuration-json must contain a JSON object")
    return configuration


def main():
    parser = argparse.ArgumentParser(
        description="Bosca TFRS recommendation model trainer"
    )
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    # Serve command
    serve_parser = subparsers.add_parser("serve", help="Run as HTTP service")
    serve_parser.add_argument("--port", type=int, default=8090, help="HTTP port")
    serve_parser.add_argument("--trino-host", default="trino", help="Default Trino host")
    serve_parser.add_argument("--trino-port", type=int, default=8089, help="Default Trino port")
    serve_parser.add_argument("--trino-user", default="bosca", help="Default Trino user")
    serve_parser.add_argument("--trino-catalog", default="warehouse", help="Default Trino catalog")
    serve_parser.add_argument("--lookback-days", type=int, default=90, help="Default lookback days")
    serve_parser.add_argument("--model-dir", default="/models/recommender", help="Model output directory")
    serve_parser.add_argument("--embedding-dim", type=int, default=64, help="Embedding dimension")
    serve_parser.add_argument("--epochs", type=int, default=50, help="Max training epochs per stage (early-stopped at convergence)")
    serve_parser.add_argument("--batch-size", type=int, default=8192, help="Training batch size")
    serve_parser.add_argument("--learning-rate", type=float, default=0.01, help="Learning rate")
    serve_parser.add_argument("--top-k", type=int, default=50, help="Top K candidates")
    serve_parser.add_argument("--use-scann", action="store_true", help="Use ScaNN for learned personalized indexes (content similarity remains exact)")
    serve_parser.add_argument("--min-interactions", type=int, default=100, help="Minimum interactions to train")
    serve_parser.add_argument("--bosca-url", default=os.environ.get("BOSCA_URL", "http://bosca-server:8080"), help="Bosca server URL for loading training data (env: BOSCA_URL)")
    serve_parser.add_argument("--bosca-token", default=os.environ.get("BOSCA_API_TOKEN"), help="Bosca API token for data loading (env: BOSCA_API_TOKEN)")
    serve_parser.add_argument("--artifacts-url", default=os.environ.get("ARTIFACTS_URL", "http://bosca-artifacts-server:8084"), help="Artifacts server URL for model push (env: ARTIFACTS_URL)")
    serve_parser.add_argument("--artifacts-token", default=os.environ.get("ARTIFACTS_API_TOKEN"), help="Artifacts push token, scoped artifacts:ml:{ns}/*:*:push (env: ARTIFACTS_API_TOKEN)")
    serve_parser.add_argument("--artifacts-pull-token", default=os.environ.get("ARTIFACTS_PULL_API_TOKEN"), help="Artifacts pull token used for version discovery and champion hydration (env: ARTIFACTS_PULL_API_TOKEN)")
    serve_parser.add_argument("--artifacts-namespace", default=os.environ.get("ARTIFACTS_NAMESPACE", "model"), help="Artifact namespace models are pushed under (env: ARTIFACTS_NAMESPACE)")

    # Train command (direct CLI)
    train_parser = subparsers.add_parser("train", help="Run training directly")
    train_parser.add_argument("--trino-host", default="localhost", help="Trino host")
    train_parser.add_argument("--trino-port", type=int, default=8089, help="Trino port")
    train_parser.add_argument("--trino-user", default="bosca", help="Trino user")
    train_parser.add_argument("--trino-catalog", default="warehouse", help="Trino catalog")
    train_parser.add_argument("--lookback-days", type=int, default=90, help="Lookback days")
    train_parser.add_argument("--model-dir", default="/models/recommender", help="Model output directory")
    train_parser.add_argument("--embedding-dim", type=int, default=64, help="Embedding dimension")
    train_parser.add_argument("--epochs", type=int, default=50, help="Max training epochs per stage (early-stopped at convergence)")
    train_parser.add_argument("--batch-size", type=int, default=8192, help="Training batch size")
    train_parser.add_argument("--learning-rate", type=float, default=0.01, help="Learning rate")
    train_parser.add_argument("--top-k", type=int, default=50, help="Top K candidates")
    train_parser.add_argument("--use-scann", action="store_true", help="Use ScaNN for learned personalized indexes (content similarity remains exact)")
    train_parser.add_argument("--min-interactions", type=int, default=100, help="Minimum interactions")
    train_parser.add_argument("--bosca-url", default=os.environ.get("BOSCA_URL", "http://bosca-server:8080"), help="Bosca server URL for loading training data (env: BOSCA_URL)")
    train_parser.add_argument("--bosca-token", default=os.environ.get("BOSCA_API_TOKEN"), help="Bosca API token for data loading (env: BOSCA_API_TOKEN)")
    train_parser.add_argument("--artifacts-url", default=os.environ.get("ARTIFACTS_URL", "http://bosca-artifacts-server:8084"), help="Artifacts server URL for model push (env: ARTIFACTS_URL)")
    train_parser.add_argument("--artifacts-token", default=os.environ.get("ARTIFACTS_API_TOKEN"), help="Artifacts push token (env: ARTIFACTS_API_TOKEN)")
    train_parser.add_argument("--artifacts-pull-token", default=os.environ.get("ARTIFACTS_PULL_API_TOKEN"), help="Artifacts pull token used for version discovery and champion hydration (env: ARTIFACTS_PULL_API_TOKEN)")
    train_parser.add_argument("--artifacts-namespace", default=os.environ.get("ARTIFACTS_NAMESPACE", "model"), help="Artifact namespace (env: ARTIFACTS_NAMESPACE)")
    train_parser.add_argument(
        "--configuration-json",
        default=os.environ.get("ML_TRAINER_CONFIGURATION_JSON"),
        help="JSON object overriding this run's training configuration (env: ML_TRAINER_CONFIGURATION_JSON)",
    )

    args = parser.parse_args()

    if args.command == "serve":
        default_config = {
            "trino_host": args.trino_host,
            "trino_port": args.trino_port,
            "trino_user": args.trino_user,
            "trino_catalog": args.trino_catalog,
            "lookback_days": args.lookback_days,
            "model_dir": args.model_dir,
            "embedding_dim": args.embedding_dim,
            "epochs": args.epochs,
            "batch_size": args.batch_size,
            "learning_rate": args.learning_rate,
            "top_k": args.top_k,
            "use_scann": args.use_scann,
            "min_interactions": args.min_interactions,
            "bosca_url": args.bosca_url,
            "bosca_token": args.bosca_token,
            "artifacts_url": args.artifacts_url,
            "artifacts_token": args.artifacts_token,
            "artifacts_pull_token": args.artifacts_pull_token,
            "artifacts_namespace": args.artifacts_namespace,
        }
        run_server(args.port, default_config)

    elif args.command == "train":
        config = {
            "trino_host": args.trino_host,
            "trino_port": args.trino_port,
            "trino_user": args.trino_user,
            "trino_catalog": args.trino_catalog,
            "lookback_days": args.lookback_days,
            "model_dir": args.model_dir,
            "embedding_dim": args.embedding_dim,
            "epochs": args.epochs,
            "batch_size": args.batch_size,
            "learning_rate": args.learning_rate,
            "top_k": args.top_k,
            "use_scann": args.use_scann,
            "min_interactions": args.min_interactions,
            "bosca_url": args.bosca_url,
            "bosca_token": args.bosca_token,
            "artifacts_url": args.artifacts_url,
            "artifacts_token": args.artifacts_token,
            "artifacts_pull_token": args.artifacts_pull_token,
            "artifacts_namespace": args.artifacts_namespace,
        }
        config.update(parse_configuration_json(args.configuration_json))
        result = run_training(config)
        log.info("Result: %s", json.dumps(result, indent=2))
        if result["status"] == "failed":
            sys.exit(1)

    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
