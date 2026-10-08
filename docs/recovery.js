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

export class IndexedDraftRecovery {
  constructor(indexedDB, key = 'mnx-draft-v1') { this.indexedDB = indexedDB; this.key = key; }
  async open() {
    if (!this.database) this.database = new Promise((resolve, reject) => {
      const request = this.indexedDB.open(this.key, 1);
      request.onupgradeneeded = () => request.result.createObjectStore('drafts');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => { this.database = null; reject(request.error); };
      request.onblocked = () => { this.database = null; reject(new Error('Draft storage is blocked by another tab.')); };
    });
    return this.database;
  }
  async read() {
    const db = await this.open();
    const records = await new Promise((resolve, reject) => {
      const tx = db.transaction('drafts', 'readonly'), store = tx.objectStore('drafts');
      const current = store.get('current'), previous = store.get('previous');
      tx.oncomplete = () => resolve([current.result, previous.result]);
      tx.onabort = tx.onerror = () => reject(tx.error || new Error('Draft read failed.'));
    });
    const decoder = new DraftRecovery(null);
    for (const raw of records) { try { const result = decoder.decode(raw); if (result) return result; } catch { /* previous slot */ } }
    return null;
  }
  async write(map, file, dirty) {
    // Serialize before awaiting storage, so later edits cannot alter this checkpoint.
    let record;
    const encoder = new DraftRecovery({getItem:()=>null, setItem:(_,value)=>{record=value;}});
    encoder.write(map, file, dirty);
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('drafts', 'readwrite'), store = tx.objectStore('drafts');
      const request = store.get('current');
      request.onsuccess = () => {
        try { if (encoder.decode(request.result)) store.put(request.result, 'previous'); } catch { /* retain valid previous */ }
        store.put(record, 'current');
      };
      tx.oncomplete = () => resolve();
      tx.onabort = tx.onerror = () => reject(tx.error || new Error('Draft write failed.'));
    });
  }
}
