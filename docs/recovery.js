import { parseMap, serializeMap } from './graph.js';
// Two slots retain the previous valid draft if the newest record is damaged.
// OAuth tokens are never included in recovery records.
export class DraftRecovery {
  constructor(storage, key = 'mnx.draft.v1') { this.storage = storage; this.key = key; }
  decode(raw) {
    if (!raw) return null;
    const record = JSON.parse(raw);
    if (record.version !== 1 || typeof record.dirty !== 'boolean') throw new Error('Invalid draft record.');
    record.map = parseMap(record.raw); delete record.raw;
    return record;
  }
  read() {
    for (const key of [this.key, this.key + '.previous']) {
      try { const record = this.decode(this.storage.getItem(key)); if (record) return record; } catch { /* try previous valid slot */ }
    }
    return null;
  }
  write(map, file, dirty) {
    const raw = serializeMap(map);
    const safeFile = file ? Object.fromEntries(['id','name','version','resourceKey','webViewLink','capabilities'].filter(k => file[k] !== undefined).map(k => [k,file[k]])) : null;
    const previous = this.storage.getItem(this.key);
    try { if (this.decode(previous)) this.storage.setItem(this.key + '.previous', previous); } catch { /* retain previous slot */ }
    this.storage.setItem(this.key, JSON.stringify({ version: 1, raw, file: safeFile, dirty, savedAt: Date.now() }));
  }
}
