# Reliability and recovery

The project preserves the SQLite proposal → review → accept core. Reliability
means detecting invalid state, preventing avoidable overwrites and demonstrating
recovery; no component promises universal failure prevention.

## Implemented safeguards

- Browser map validation rejects duplicate IDs, dangling edges, cyclic parents,
  invalid attribute/dimension types and files over the supported limits.
- The editor retains the latest and previous valid local draft, offers recovery
  on reload, and warns when browser storage is unavailable. Tokens are excluded.
- Drive saves require matching versions and a conditional-write ETag. Missing
  ETags block writes. Save success requires content readback. Network requests
  time out; writes are never retried automatically.
- Editing a classified statement resets verification and keeps its provenance
  and previous statement in history. Editorial acceptance is independent of truth.
- SQLite backups use the SQLite backup API, include committed WAL contents,
  run integrity checks and validate core metadata before atomic publication.
  Existing destinations cannot be replaced. Restore is tested into a new path.

## SQLite recovery drill

From `tools/knowledge-core`, run:

```sh
python -m mnx_knowledge.recovery backup /path/live.sqlite3 /path/new-backup.sqlite3
python -m mnx_knowledge.recovery verify /path/new-backup.sqlite3
python -m mnx_knowledge.recovery restore /path/new-backup.sqlite3 /path/new-restored.sqlite3
```

Record the emitted SHA-256 digest separately in trusted backup inventory. The
backup includes contributor/service tables when present; treat it as private.
Integrity checking detects structural corruption, not false claims or all logical
errors. Restore into a new file, inspect the graph and review history, stop the
service, then point the service at the restored file. Never replace a live database.
Do not copy only a live database file while ignoring its WAL.

## Next work, in priority order

1. Configure off-site backup retention and scheduled restore drills for the actual
   deployment. Define acceptable data loss and recovery time; alert on stale backups.
2. Exercise Android imports, transaction failures, disk exhaustion and migrations
   with the unified map. Test export/import preservation of epistemic metadata.
3. Audit service authentication, key revocation, request limits and review access.
   Test concurrent reviewers and snapshot consistency under sustained writes.
4. Add deployment health checks, error monitoring and credential-expiry alerts.
   Configure snapshot publishing once the reference instance is deployed.
5. Provide structured claim/evidence editing and reviewer actions in the browser.
   Add source-specific checks; never promote an unsupported claim automatically.

Browser recovery can be lost by clearing storage or changing browser/origin, and
space is limited. Downloaded or off-site backups remain necessary. Drive writes
may commit before a network timeout; a failed response does not prove no write
occurred. Conditional-save support must be verified against real Google credentials.
