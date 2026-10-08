"""Use python -m mnx_knowledge --help. No installation required."""

import argparse
import json
import sqlite3
import sys
from pathlib import Path

from .store import Store, ValidationError
from .interchange import export_mnxj


def main():
    parser = argparse.ArgumentParser(description="Evidence-linked concept graph prototype")
    parser.add_argument("--db", default="mnx-knowledge.sqlite3")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init")
    submit = commands.add_parser("submit")
    submit.add_argument("file", help="JSON proposal path, or - for stdin")
    review = commands.add_parser("review")
    review.add_argument("id")
    review.add_argument("--reviewer", required=True)
    review.add_argument("--decision", choices=("accept", "reject"), required=True)
    review.add_argument("--reason", required=True)
    commands.add_parser("export")
    commands.add_parser("history")
    mnxj = commands.add_parser("export-mnxj")
    mnxj.add_argument("--name", default="Shared Knowledge")
    similar = commands.add_parser("similar")
    similar.add_argument("observation_id")
    similar.add_argument("--limit", type=int, default=5)
    args = parser.parse_args()
    store = None
    try:
        store = Store(args.db)
        if args.command == "init":
            result = {"database": args.db, "revision": store.revision}
        elif args.command == "submit":
            raw = sys.stdin.read() if args.file == "-" else Path(args.file).read_text(encoding="utf-8")
            result = store.submit(json.loads(raw))
        elif args.command == "review":
            result = store.review(args.id, args.reviewer, args.decision == "accept", args.reason)
        elif args.command == "export":
            result = store.snapshot()
        elif args.command == "history":
            result = store.history()
        elif args.command == "export-mnxj":
            result = export_mnxj(store.snapshot(), store.history(), args.name)
        else:
            result = store.similar(args.observation_id, args.limit)
        print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
        return 0
    except (ValidationError, OSError, sqlite3.Error, json.JSONDecodeError) as exc:
        print(json.dumps({"error": str(exc)}), file=sys.stderr)
        return 1
    finally:
        if store is not None:
            store.close()


if __name__ == "__main__":
    sys.exit(main())
