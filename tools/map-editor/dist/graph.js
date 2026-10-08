export const MAX_BYTES = 64 * 1024 * 1024;
export const NODE_TYPES = ['IDENTITY', 'MEMORY', 'KNOWLEDGE', 'STATE', 'AFFECT', 'PERSONALITY', 'BELIEF', 'VALUE', 'RELATIONSHIP', 'DRIFT_RULE', 'CUSTOM'];
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const text = value => typeof value === 'string' && value.trim().length > 0;
const finite = value => typeof value === 'number' && Number.isFinite(value);

export function validateMap(map) {
  requireValue(object(map), 'The map must be a JSON object.');
  requireValue(map.schema?.family === 'mnx.interchange' && map.schema?.version?.major === 1, 'Use a version 1 .mnxj map exported from MnxMindMaker.');
  requireValue(Number.isInteger(map.schema.version.minor) && map.schema.version.minor >= 0, 'Invalid map version.');
  requireValue(object(map.compatibility) && Array.isArray(map.compatibility.forward_compat_extensions), 'Missing compatibility information.');
  const reader = map.compatibility.min_reader_version;
  requireValue(reader?.major === 1 && reader?.minor === 0, 'This map requires a newer reader.');
  requireValue(map.metadata === undefined || (object(map.metadata) && Object.values(map.metadata).every(v => typeof v === 'string')), 'Map metadata must contain text values.');
  const graph = map.graph;
  requireValue(object(graph) && text(graph.id) && text(graph.name), 'Missing map ID or name.');
  requireValue(finite(graph.created_at) && finite(graph.modified_at), 'Invalid map timestamps.');
  requireValue(Array.isArray(graph.nodes) && Array.isArray(graph.edges), 'The map must contain node and connection arrays.');
  requireValue(graph.nodes.length <= 10000 && graph.edges.length <= 30000, 'This map is too large for the browser editor.');
  const nodes = new Set();
  for (const node of graph.nodes) {
    requireValue(object(node) && text(node.id) && text(node.label), 'Every node needs an ID and label.');
    requireValue(!nodes.has(node.id), 'Duplicate node ID.');
    requireValue(finite(node.x) && finite(node.y), 'Node positions must be finite numbers.');
    requireValue(text(node.type), 'Every node needs a type.');
    requireValue(node.description === undefined || typeof node.description === 'string', 'Node descriptions must be text.');
    requireValue(node.attributes === undefined || (object(node.attributes) && Object.values(node.attributes).every(v => typeof v === 'string')), 'Node attributes must contain text values.');
    requireValue(node.dimensions === undefined || (object(node.dimensions) && Object.values(node.dimensions).every(finite)), 'Node dimensions must contain finite numbers.');
    nodes.add(node.id);
  }
  for (const node of graph.nodes) {
    requireValue(node.parent_id == null || nodes.has(node.parent_id), 'A parent node is missing.');
  }
  const parents = new Map(graph.nodes.map(node => [node.id, node.parent_id]));
  const checked = new Set();
  for (const node of graph.nodes) {
    const path = new Set(); let id = node.id;
    while (id != null && !checked.has(id)) {
      requireValue(!path.has(id), 'Parent relationships must not contain cycles.');
      path.add(id); id = parents.get(id);
    }
    for (const visited of path) checked.add(visited);
  }
  const edges = new Set();
  for (const edge of graph.edges) {
    requireValue(object(edge) && text(edge.id) && !edges.has(edge.id), 'Invalid or duplicate connection ID.');
    requireValue(nodes.has(edge.from_node_id) && nodes.has(edge.to_node_id), 'A connection points to a missing node.');
    requireValue(finite(edge.strength), 'Connection strength must be a finite number.');
    requireValue(edge.label === undefined || typeof edge.label === 'string', 'Connection labels must be text.');
    edges.add(edge.id);
  }
  // Internal content references must resolve in this same map. External URLs
  // remain provenance and do not substitute for retained content.
  const pending = [map];
  while (pending.length) {
    const value = pending.pop();
    if (typeof value === 'string') {
      for (const match of value.matchAll(/mnx:\/\/node\/([A-Za-z0-9:_-]+)/g)) {
        requireValue(nodes.has(match[1]), 'An internal content reference points to a missing node: ' + match[1]);
      }
    } else if (value && typeof value === 'object') pending.push(...Object.values(value));
  }
  return map;
}

export function parseMap(raw) {
  requireValue(new TextEncoder().encode(raw).length <= MAX_BYTES, 'Maps must be smaller than 64 MiB.');
  return validateMap(JSON.parse(raw));
}
export function createMap(name = 'Untitled map') {
  const now = Date.now();
  return { schema: { family: 'mnx.interchange', version: { major: 1, minor: 0 } },
    compatibility: { min_reader_version: { major: 1, minor: 0 }, forward_compat_extensions: [], migration_hint: null },
    metadata: {}, graph: { id: crypto.randomUUID(), name, created_at: now, modified_at: now, nodes: [], edges: [] } };
}
export function addNode(map, label = 'New concept', x = 0, y = 0) {
  const node = { id: crypto.randomUUID(), label, type: 'KNOWLEDGE', description: '', x, y,
    parent_id: null, attributes: {}, dimensions: {}, is_expanded: true };
  map.graph.nodes.push(node);
  return node;
}
export function reviseNode(node, { label, description, type }) {
  const before = { label: node.label, description: node.description || '', type: node.type };
  if (before.label === label && before.description === description && before.type === type) return;
  Object.assign(node, { label, description, type });
  // Editing a statement invalidates inherited verification, not its provenance.
  if (node.attributes?.epistemic_schema_version === '1') {
    const a = node.attributes;
    let history = []; try { history = JSON.parse(a.history_json || '[]'); } catch { /* retain malformed original separately */ }
    if (!Array.isArray(history)) history = [];
    history.push({ date: new Date().toISOString(), actor: 'browser editor', action: 'Statement edited; verification reset', previous: before, previous_verification: a.verification_status });
    a.history_json = JSON.stringify(history); a.verification_status = 'unchecked';
    a.evidence_status = 'needs_revalidation'; a.epistemic_status = 'unverified';
    a.original_label = label.replace(/^\[[^\]]+\]\s*/, '');
    node.label = '[UNVERIFIED] ' + a.original_label;
    node.description = 'Verification reset after editing. Prior sources and history retained; recheck support for the new statement.\n\n' + description;
  }
}
export function removeNode(map, id) {
  map.graph.nodes = map.graph.nodes.filter(node => node.id !== id);
  map.graph.edges = map.graph.edges.filter(edge => edge.from_node_id !== id && edge.to_node_id !== id);
  for (const node of map.graph.nodes) if (node.parent_id === id) node.parent_id = null;
}
export function serializeMap(map) {
  validateMap(map); const raw = JSON.stringify(map, null, 2);
  requireValue(new TextEncoder().encode(raw).length <= MAX_BYTES, 'Maps must be smaller than 64 MiB.');
  return raw;
}

export function parseDriveLink(raw, kind = 'folder') {
  let url;
  try { url = new URL(raw.trim()); } catch { throw new Error('Paste a Google Drive ' + kind + ' link.'); }
  requireValue(url.protocol === 'https:' && url.hostname === 'drive.google.com', 'Use a link from drive.google.com.');
  const match = kind === 'folder' ? url.pathname.match(/\/folders\/([A-Za-z0-9_-]+)/) : url.pathname.match(/\/file\/d\/([A-Za-z0-9_-]+)/);
  const id = match?.[1] || (url.pathname === '/open' ? url.searchParams.get('id') : null);
  requireValue(id && /^[A-Za-z0-9_-]+$/.test(id), 'This is not a Google Drive ' + kind + ' link.');
  return { id, resourceKey: url.searchParams.get('resourcekey') || '' };
}
export function editorLink(base, folder, file) {
  const url = new URL(base);
  url.search = '';
  const params = new URLSearchParams();
  if (folder) { params.set('folder', folder.id); if (folder.resourceKey) params.set('folderkey', folder.resourceKey); }
  if (file) { params.set('file', file.id); if (file.resourceKey) params.set('filekey', file.resourceKey); }
  url.hash = params.toString();
  return url.href;
}
