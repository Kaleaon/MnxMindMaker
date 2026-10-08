"""Consistent verified backups; restore only to a new, offline destination."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile


def verify(path):
    with sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True) as db:
        if [r[0] for r in db.execute('PRAGMA integrity_check')] != ['ok']:
            raise ValueError('SQLite integrity check failed')
        tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {'entities', 'proposals', 'metadata'} <= tables:
            raise ValueError('Not an MNX knowledge-core database')
        row = db.execute('SELECT revision, graph_id FROM metadata WHERE id=1').fetchone()
        if row is None:
            raise ValueError('Missing metadata')
        return dict(revision=row[0], graph_id=row[1], entities=db.execute('SELECT COUNT(*) FROM entities').fetchone()[0], proposals=db.execute('SELECT COUNT(*) FROM proposals').fetchone()[0])


def backup(source, destination):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    if source == destination or destination.exists():
        raise ValueError('Destination must be a new file; existing files are never overwritten')
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.mnx-backup-', dir=destination.parent)
    os.close(fd)
    try:
        with sqlite3.connect(source.as_uri() + '?mode=ro', uri=True, timeout=10) as src:
            with sqlite3.connect(temporary) as target:
                src.backup(target)
        result = verify(temporary)
        with open(temporary, 'rb') as stream:
            os.fsync(stream.fileno())
            result['sha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        os.link(temporary, destination)  # atomic no-clobber publication
        if os.name == 'posix':
            fd = os.open(destination.parent, os.O_RDONLY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        return result
    finally:
        Path(temporary).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['backup', 'restore', 'verify'])
    parser.add_argument('source')
    parser.add_argument('destination', nargs='?')
    args = parser.parse_args()
    if args.action != 'verify' and not args.destination:
        parser.error('A new destination path is required')
    print(json.dumps(verify(args.source) if args.action == 'verify' else backup(args.source, args.destination), sort_keys=True))


if __name__ == '__main__':
    main()
