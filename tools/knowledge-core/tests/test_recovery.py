import sqlite3
import tempfile
import unittest
from pathlib import Path
from mnx_knowledge.store import Store
from mnx_knowledge.recovery import backup, verify


class RecoveryTests(unittest.TestCase):
    def test_wal_backup_restore_preserves_committed_state(self):
        with tempfile.TemporaryDirectory() as directory:
            source, saved, restored = [Path(directory)/n for n in ['live', 'backup', 'restored']]
            store = Store(source)
            try:
                store.db.execute('PRAGMA journal_mode=WAL')
                with store.db:
                    store.db.execute("INSERT INTO entities VALUES ('concepts', 'example', '{}')")
                    store.db.execute('UPDATE metadata SET revision=7 WHERE id=1')
                result = backup(source, saved)
                self.assertEqual((result['revision'], result['entities']), (7, 1))
                backup(saved, restored)
                self.assertEqual(verify(restored), verify(source))
                self.assertEqual(store.snapshot()['revision'], 7)
            finally:
                store.close()

    def test_existing_destination_is_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory)/'source', Path(directory)/'target'
            Store(source).close(); target.write_bytes(b'keep')
            with self.assertRaises(ValueError):
                backup(source, target)
            self.assertEqual(target.read_bytes(), b'keep')

    def test_invalid_source_is_not_published(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory)/'source', Path(directory)/'target'
            with sqlite3.connect(source) as db:
                db.execute('CREATE TABLE other (id INTEGER)')
            with self.assertRaises(ValueError):
                backup(source, target)
            self.assertFalse(target.exists())
            self.assertFalse(list(Path(directory).glob('.mnx-backup-*')))
