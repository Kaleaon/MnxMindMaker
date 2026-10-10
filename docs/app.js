import { MAX_BYTES, NODE_TYPES, addNode, createMap, editorLink, parseDriveLink, parseMap, removeNode, reviseNode, serializeMap, validateMap } from './graph.js';
import { ConflictError, DriveClient, connectGoogle } from './drive.js';
import { DraftRecovery, IndexedDraftRecovery } from './recovery.js';

export class ModalDialogController {
  constructor(dialogElement) {
    this.dialog = typeof dialogElement === 'string' ? (typeof document !== 'undefined' ? document.getElementById(dialogElement) : null) : dialogElement;
    this.triggerElement = null;
    if (this.dialog) {
      this._ensureAccessibleName();
      this._bindCloseHandler();
    }
  }

  _ensureAccessibleName() {
    if (!this.dialog.hasAttribute('aria-labelledby') && !this.dialog.hasAttribute('aria-label')) {
      const heading = this.dialog.querySelector('h1, h2, h3');
      if (heading) {
        if (!heading.id) heading.id = 'dialog-heading-' + Math.random().toString(36).slice(2, 9);
        this.dialog.setAttribute('aria-labelledby', heading.id);
      }
    }
  }

  _bindCloseHandler() {
    this.dialog.addEventListener('close', () => {
      if (this.triggerElement && typeof this.triggerElement.focus === 'function') {
        this.triggerElement.focus();
        this.triggerElement = null;
      }
    });
  }

  open(triggerElement = (typeof document !== 'undefined' ? document.activeElement : null)) {
    this.triggerElement = triggerElement;
    if (this.dialog) this.dialog.showModal();
  }

  close() {
    if (this.dialog) this.dialog.close();
  }
}

const settingsModal = typeof document !== 'undefined' ? new ModalDialogController('settings-dialog') : null;
const shareModal = typeof document !== 'undefined' ? new ModalDialogController('share-dialog') : null;

const $ = id => typeof document !== 'undefined' ? document.getElementById(id) : null;
const state = { map: createMap(), file: null, folder: null, files: [], drive: null, selected: null, dirty: false,
  busy: false, undo: [], box: [-500, -350, 1000, 700], connected: false, generation: 0 };
const config = typeof window !== 'undefined' ? (window.MNX_CONFIG || {}) : {};
let storage; try { storage = typeof window !== 'undefined' ? window.localStorage : null; } catch { /* storage may be blocked */ }
let clientId = config.googleClientId || '';
try { clientId ||= storage?.getItem('mnx.googleClientId') || ''; } catch { /* settings storage unavailable */ }
let recovery = storage ? new DraftRecovery(storage) : null;
try { if (typeof window !== 'undefined' && window.indexedDB) recovery = new IndexedDraftRecovery(window.indexedDB); } catch { /* retain localStorage fallback */ }
let draftTimer;
async function checkpoint() {
  clearTimeout(draftTimer);
  try { if (!recovery) throw new Error('Storage unavailable'); await recovery.write(state.map, state.file, state.dirty); }
  catch { say('Local recovery is unavailable or full. Download your draft to keep a backup.', true, true); }
}
let messageTimer;
let requested = new URLSearchParams(typeof location !== 'undefined' ? location.hash.slice(1) : '');
const validId = id => typeof id === 'string' && /^[A-Za-z0-9_-]{1,250}$/.test(id);
const requestedFile = validId(requested.get('file')) ? { id: requested.get('file'), resourceKey: requested.get('filekey') || '' } : null;
const initialFolder = validId(requested.get('folder')) ? { id: requested.get('folder'), resourceKey: requested.get('folderkey') || '' } : config.defaultFolder;
if (typeof document !== 'undefined') {
  if (initialFolder?.id && $('folder-link')) $('folder-link').value = 'https://drive.google.com/drive/folders/' + initialFolder.id + (initialFolder.resourceKey ? '?resourcekey=' + encodeURIComponent(initialFolder.resourceKey) : '');
  if ($('node-type')) { for (const type of NODE_TYPES) $('node-type').add(new Option(type.replaceAll('_', ' '), type)); }
}

function editable() { return !state.busy && (!state.file || state.file.capabilities?.canEdit === true); }
function say(message, error = false, permanent = false) {
  clearTimeout(messageTimer);
  $('message').textContent = message; $('message').classList.toggle('error', error); $('message').hidden = false;
  if (!permanent) messageTimer = setTimeout(() => { $('message').hidden = true; }, error ? 12000 : 6500);
}
async function action(fn) {
  if (state.busy) return;
  state.busy = true; renderControls();
  try { await fn(); } catch (error) { say(error.message || 'Something went wrong. Your draft is still here.', true, error instanceof ConflictError); }
  finally { state.busy = false; render(); }
}
function remember() {
  state.undo.push(serializeMap(state.map));
  let bytes = state.undo.reduce((total, raw) => total + new TextEncoder().encode(raw).length, 0);
  while (state.undo.length > 1 && (state.undo.length > 30 || bytes > 20 * 1024 * 1024)) bytes -= new TextEncoder().encode(state.undo.shift()).length;
}
function changed() { state.dirty = true; state.generation++; state.map.graph.modified_at = Date.now(); clearTimeout(draftTimer); draftTimer = setTimeout(checkpoint, 250); }
function mutate(fn) {
  if (!editable()) return;
  let rollback;
  try { remember(); rollback = state.undo.at(-1); fn(); validateMap(state.map); changed(); render(); }
  catch (error) {
    if (rollback) { state.map = parseMap(rollback); state.undo.pop(); state.selected = null; render(); }
    say(error.message || 'The edit was rejected; the previous map is retained.', true, true);
  }
}
function canLeave() { return !state.dirty || confirm('This map has unsaved changes. Download or save your draft to keep them. Discard changes and continue?'); }
function updateLink() { history.replaceState(null, '', editorLink(location.href, state.folder, state.file)); }
function loadMap(map, file = null) {
  state.map = map; state.file = file; state.selected = null; state.undo = []; state.dirty = false; state.generation++; fit(); updateLink(); render(); checkpoint();
}
function download() {
  const blob = new Blob([serializeMap(state.map)], { type: 'application/json' });
  const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url;
  anchor.download = (state.map.graph.name.replace(/[\\/:*?"<>|]/g, '_') || 'map') + '.mnxj';
  anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function renderControls() {
  const canEdit = editable();
  $('map-name').disabled = !canEdit;
  $('connect-button').textContent = state.connected ? 'Reconnect Google Drive' : 'Connect Google Drive';
  $('connect-button').disabled = state.busy; $('disconnect-button').hidden = !state.connected;
  $('disconnect-button').disabled = state.busy;
  $('open-folder-button').disabled = state.busy; $('refresh-button').disabled = state.busy || !state.folder || !state.drive;
  $('new-map-button').disabled = state.busy; $('import-button').disabled = state.busy;
  const canSave = state.drive && (state.file ? state.file.capabilities?.canEdit : state.folder?.capabilities?.canAddChildren);
  $('save-button').disabled = state.busy || !canSave;
  $('save-button').textContent = state.busy ? 'Working…' : state.file ? 'Save to Drive' : 'Save new map';
  $('share-button').disabled = state.busy || !state.file || !state.drive;
  $('add-node-button').disabled = !canEdit; $('empty-add-button').disabled = !canEdit;
  if ($('empty-ai-button')) $('empty-ai-button').disabled = !canEdit;
  if ($('empty-import-button')) $('empty-import-button').disabled = state.busy;
  $('undo-button').disabled = !canEdit || !state.undo.length;
  for (const id of ['node-label', 'node-type', 'node-description', 'apply-node-button', 'delete-node-button', 'edge-target', 'edge-label', 'add-edge-button']) $(id).disabled = !canEdit;
  $('download-button').disabled = state.busy;
  $('save-state').textContent = state.file ? (state.file.capabilities?.canEdit ? (state.dirty ? 'Unsaved changes' : 'Saved in Google Drive') : 'View only · Google Drive') : 'Local draft · not saved to Drive';
  if (state.busy) $('save-state').textContent = 'Working…';
}
function element(tag, attrs = {}, text) {
  const item = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [key, value] of Object.entries(attrs)) item.setAttribute(key, value);
  if (text !== undefined) item.textContent = text;
  return item;
}
function renderCanvas() {
  $('canvas').setAttribute('viewBox', state.box.join(' '));
  $('nodes').replaceChildren(); $('connections').replaceChildren();
  const nodes = new Map(state.map.graph.nodes.map(node => [node.id, node]));
  for (const edge of state.map.graph.edges) {
    const a = nodes.get(edge.from_node_id), b = nodes.get(edge.to_node_id);
    if (!a || !b) continue;
    const line = element('line', { x1: a.x, y1: a.y, x2: b.x, y2: b.y, class: 'edge-line' });
    $('connections').append(line);
    if (edge.label) $('connections').append(element('text', { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 - 7, 'text-anchor': 'middle', class: 'edge-text' }, edge.label.length > 30 ? edge.label.slice(0, 29) + '…' : edge.label));
  }
  for (const node of state.map.graph.nodes) {
    const group = element('g', { class: 'graph-node' + (state.selected === node.id ? ' selected' : ''), transform: `translate(${node.x} ${node.y})`, 'data-id': node.id, role: 'button', tabindex: '0', 'aria-label': node.label });
    group.append(element('rect', { x: -95, y: -35, width: 190, height: 70, rx: 10 }));
    group.append(element('text', { x: -78, y: -10, class: 'node-kind' }, node.type.replaceAll('_', ' ')));
    group.append(element('text', { x: -78, y: 14 }, node.label.length > 22 ? node.label.slice(0, 21) + '…' : node.label));
    group.append(element('title', {}, node.label + (node.description ? '\n' + node.description : '')));
    group.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); state.selected = node.id; render(); $('node-label').focus(); } });
    $('nodes').append(group);
  }
  $('empty-canvas').hidden = state.map.graph.nodes.length > 0;
  $('map-count').textContent = `${nodes.size} concepts · ${state.map.graph.edges.length} connections`;
}
function renderLibrary() {
  $('folder-name').textContent = state.folder?.name || 'No folder open';
  $('folder-help').textContent = state.folder ? (state.files.length ? `${state.files.length} map${state.files.length === 1 ? '' : 's'} in this folder` : 'No .mnxj maps yet. Create one or import a map.') : 'Connect your Google account to see the maps you can access.';
  $('map-list').replaceChildren();
  for (const file of state.files) {
    const button = document.createElement('button'); button.className = 'map-item' + (state.file?.id === file.id ? ' active' : ''); button.disabled = state.busy;
    const title = document.createElement('span'); title.textContent = file.name.replace(/\.mnxj$/i, ''); button.append(title);
    const detail = document.createElement('small'); detail.textContent = file.capabilities?.canEdit ? 'Can edit' : 'View only'; button.append(detail);
    button.onclick = () => { if (canLeave()) action(async () => { const result = await state.drive.readMap(file); loadMap(result.map, result.file); }); };
    $('map-list').append(button);
  }
}
function renderInspector() {
  $('node-list').replaceChildren();
  for (const node of state.map.graph.nodes) {
    const button = document.createElement('button'); button.className = 'node-item' + (node.id === state.selected ? ' active' : ''); button.textContent = node.label;
    button.onclick = () => { state.selected = node.id; render(); }; $('node-list').append(button);
  }
  const node = state.map.graph.nodes.find(item => item.id === state.selected);
  $('node-form').hidden = !node; $('connection-section').hidden = !node; $('inspector-help').hidden = !!node;
  if (!node) return;
  $('node-label').value = node.label; $('node-description').value = node.description || '';
  if (![...$('node-type').options].some(option => option.value === node.type)) $('node-type').add(new Option(node.type, node.type));
  $('node-type').value = node.type;
  const attributes = { ...(node.attributes || {}) };
  for (const key of ['embedded_text', 'embedded_pdf_base64', 'embedded_data_json']) {
    if (attributes[key]) attributes[key] = '[Retained in this map: ' + attributes[key].length + ' characters]';
  }
  $('node-attributes').textContent = JSON.stringify({ attributes, dimensions: node.dimensions || {} }, null, 2);
  $('embedded-content').hidden = !(node.attributes?.embedded_text || node.attributes?.embedded_data_json || node.attributes?.embedded_pdf_base64);
  const sourceText = node.attributes?.embedded_text || node.attributes?.embedded_data_json || '';
  $('embedded-text').textContent = sourceText;
  $('embedded-pdf-button').hidden = !node.attributes?.embedded_pdf_base64;
  $('edge-target').replaceChildren();
  for (const other of state.map.graph.nodes) if (other.id !== node.id) $('edge-target').add(new Option(other.label, other.id));
  $('edge-list').replaceChildren();
  for (const edge of state.map.graph.edges.filter(item => item.from_node_id === node.id || item.to_node_id === node.id)) {
    const other = state.map.graph.nodes.find(item => item.id === (edge.from_node_id === node.id ? edge.to_node_id : edge.from_node_id));
    const row = document.createElement('div'); row.className = 'edge-row';
    const label = document.createElement('span'); label.textContent = (edge.from_node_id === node.id ? 'To ' : 'From ') + other.label + (edge.label ? ' · ' + edge.label : '');
    const remove = document.createElement('button'); remove.textContent = '×'; remove.setAttribute('aria-label', 'Delete connection to ' + other.label); remove.disabled = !editable();
    remove.onclick = () => mutate(() => { state.map.graph.edges = state.map.graph.edges.filter(item => item.id !== edge.id); }); row.append(label, remove); $('edge-list').append(row);
  }
}
function render() { $('map-name').value = state.map.graph.name; renderLibrary(); renderInspector(); renderControls(); renderCanvas(); }
function fit() {
  const nodes = state.map.graph.nodes;
  if (!nodes.length) { state.box = [-500, -350, 1000, 700]; return; }
  const minX = Math.min(...nodes.map(node => node.x)) - 150, minY = Math.min(...nodes.map(node => node.y)) - 110;
  const maxX = Math.max(...nodes.map(node => node.x)) + 150, maxY = Math.max(...nodes.map(node => node.y)) + 110;
  const width = Math.max(550, maxX - minX), height = Math.max(400, maxY - minY);
  state.box = [(minX + maxX - width) / 2, (minY + maxY - height) / 2, width, height];
}
function zoom(factor) { const [x,y,w,h] = state.box; state.box = [x + w * (1-factor)/2, y + h * (1-factor)/2, w*factor, h*factor]; renderCanvas(); }
function addConcept() { mutate(() => { const [x,y,w,h] = state.box; const node = addNode(state.map, 'New concept', x+w/2+(state.map.graph.nodes.length % 3)*35, y+h/2+(state.map.graph.nodes.length % 3)*30); state.selected = node.id; }); $('node-label').focus(); $('node-label').select(); }
async function openFolder(folder, openRequested = false) {
  const result = await state.drive.listFolder(folder); state.folder = result.folder; state.files = result.files; updateLink();
  if (openRequested && requestedFile && !state.dirty) { const map = await state.drive.readMap(requestedFile); loadMap(map.map, map.file); }
  say('Folder opened. Select a map or start a new one.');
}
async function refreshFolder() { const result = await state.drive.listFolder(state.folder); state.folder = result.folder; state.files = result.files; }
if (typeof document !== 'undefined') {
$('connect-button').onclick = () => {
  if (!clientId) { $('client-id').value = ''; settingsModal.open($('connect-button')); return; }
  action(async () => {
    const session = await connectGoogle(clientId); state.drive = new DriveClient(session.access_token); state.connected = true;
    const folder = $('folder-link').value ? parseDriveLink($('folder-link').value) : null;
    if (folder) {
      try { await openFolder(folder, !!requestedFile && !state.file && !state.dirty); }
      catch (error) { if (requestedFile && !state.file && !state.dirty) { const result = await state.drive.readMap(requestedFile); loadMap(result.map, result.file); say('Map opened. The parent folder is not accessible to this account.'); } else throw error; }
    } else if (requestedFile && !state.file && !state.dirty) { const result = await state.drive.readMap(requestedFile); loadMap(result.map, result.file); }
    else say('Connected to Google Drive. Paste a folder link to open it.');
  });
};
$('disconnect-button').onclick = () => {
  const token = state.drive?.token; if (token && globalThis.google?.accounts?.oauth2) google.accounts.oauth2.revoke(token, () => {});
  state.drive = null; state.connected = false; state.folder = null; state.files = []; render(); say('Disconnected. Your current map stays open as a draft; download it to keep your changes.');
};
$('folder-form').onsubmit = event => {
  event.preventDefault(); if (!state.drive) { say('Connect Google Drive first.', true); return; }
  action(async () => { const folder = parseDriveLink($('folder-link').value); await openFolder(folder); });
};
$('refresh-button').onclick = () => action(refreshFolder);
$('new-map-button').onclick = () => { if (!state.busy && canLeave()) { loadMap(createMap()); $('map-name').focus(); $('map-name').select(); } };
$('import-button').onclick = () => { if (canLeave()) $('import-input').click(); };
$('import-input').onchange = event => {
  const file = event.target.files[0]; if (!file) return;
  action(async () => { if (file.size > MAX_BYTES) throw new Error('Maps must be smaller than 64 MiB.'); loadMap(parseMap(await file.text())); say('Map imported as a local draft. Save it to your Drive folder to share it.'); });
  event.target.value = '';
};
$('map-name').onchange = () => { const name = $('map-name').value.trim(); if (!name) { say('Give your map a name.', true); $('map-name').value = state.map.graph.name; return; } mutate(() => { state.map.graph.name = name; }); };
$('download-button').onclick = download;
$('save-button').onclick = () => action(async () => {
  const map = structuredClone(state.map), generation = state.generation;
  const file = state.file ? await state.drive.saveMap(state.file, map) : await state.drive.createMap(state.folder, map);
  state.file = file; if (generation === state.generation) state.dirty = false;
  checkpoint(); updateLink(); say('Saved to Google Drive and verified.');
  try { await refreshFolder(); } catch { say('Map saved, but the folder list could not refresh. Use Refresh to try again.', true); }
});
function generateAiGraph() {
  if (!editable()) return;
  const topic = prompt('Enter a concept or topic for AI graph generation:', 'AI Mind');
  if (topic === null) return;
  const name = topic.trim() || 'AI Mind';
  mutate(() => {
    const [x, y, w, h] = state.box;
    const cx = x + w / 2, cy = y + h / 2;
    const root = addNode(state.map, name + ' Core', cx, cy - 80);
    root.type = 'IDENTITY';
    const knode = addNode(state.map, name + ' Knowledge', cx - 140, cy + 60);
    knode.type = 'KNOWLEDGE';
    const mnode = addNode(state.map, name + ' Memory', cx + 140, cy + 60);
    mnode.type = 'MEMORY';
    state.map.graph.edges.push(
      { id: crypto.randomUUID(), from_node_id: root.id, to_node_id: knode.id, label: 'includes', strength: 1 },
      { id: crypto.randomUUID(), from_node_id: root.id, to_node_id: mnode.id, label: 'recalls', strength: 1 }
    );
    state.selected = root.id;
  });
  say('AI graph generated for ' + (topic.trim() || 'AI Mind') + '.');
}
$('add-node-button').onclick = addConcept; $('empty-add-button').onclick = addConcept;
if ($('empty-import-button')) $('empty-import-button').onclick = () => { if (canLeave()) $('import-input').click(); };
if ($('empty-ai-button')) $('empty-ai-button').onclick = generateAiGraph;
$('undo-button').onclick = () => { if (!editable() || !state.undo.length) return; state.map = parseMap(state.undo.pop()); changed(); state.selected = null; render(); };
$('fit-button').onclick = () => { fit(); renderCanvas(); }; $('zoom-in-button').onclick = () => zoom(.8); $('zoom-out-button').onclick = () => zoom(1.25);
$('node-form').onsubmit = event => { event.preventDefault(); const label = $('node-label').value.trim(), description = $('node-description').value, type = $('node-type').value;
  if (!label) return say('Concept labels cannot be empty.', true);
  mutate(() => { const node = state.map.graph.nodes.find(item => item.id === state.selected); reviseNode(node, { label, description, type }); });
};
$('embedded-pdf-button').onclick = () => action(async () => {
  const node = state.map.graph.nodes.find(item => item.id === state.selected);
  const a = node?.attributes; if (!a?.embedded_pdf_base64) return;
  const bytes = Uint8Array.from(atob(a.embedded_pdf_base64), c => c.charCodeAt(0));
  const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(n => n.toString(16).padStart(2, '0')).join('');
  if (digest !== a.embedded_pdf_sha256) throw new Error('Embedded PDF failed its integrity check. Keep this map and recover a verified version.');
  const url = URL.createObjectURL(new Blob([bytes], {type:'application/pdf'}));
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = a.embedded_pdf_name || 'source.pdf'; anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
});
$('delete-node-button').onclick = () => { if (confirm('Delete this concept and its connections?')) mutate(() => { removeNode(state.map, state.selected); state.selected = null; }); };
$('edge-form').onsubmit = event => { event.preventDefault(); const target = $('edge-target').value, label = $('edge-label').value.trim(); if (!target) return;
  mutate(() => { state.map.graph.edges.push({ id: crypto.randomUUID(), from_node_id: state.selected, to_node_id: target, label, strength: 1 }); }); $('edge-label').value = '';
};
$('settings-button').onclick = () => { $('client-id').value = clientId; settingsModal.open($('settings-button')); };
$('settings-form').onsubmit = event => { event.preventDefault(); const value = $('client-id').value.trim(); if (!/^[\w.-]+\.apps\.googleusercontent\.com$/.test(value)) return say('Use a Google OAuth web client ID ending in .apps.googleusercontent.com.', true);
  clientId = value; try { storage?.setItem('mnx.googleClientId', value); } catch { /* retain session setting */ } settingsModal.close(); say('Settings saved. Connect Google Drive when you’re ready.');
};
for (const button of document.querySelectorAll('[data-close]')) button.onclick = () => $(button.dataset.close).close();
$('share-button').onclick = () => {
  $('share-link').value = editorLink(location.href, state.folder, state.file);
  $('link-access').value = 'keep'; for (const option of $('link-access').options) option.disabled = option.value !== 'keep' && !state.file.capabilities?.canShare;
  $('drive-file-link').href = state.file.webViewLink || 'https://drive.google.com/file/d/' + state.file.id + '/view';
  $('sharing-help').textContent = state.dirty ? 'You have unsaved edits. Save them first if you want others to see those changes.' : 'Changing link access affects the Drive file. Your organization may restrict public sharing.';
  shareModal.open($('share-button'));
};
$('share-form').onsubmit = event => {
  event.preventDefault(); $('apply-share-button').disabled = true;
  action(async () => {
    try {
      const role = $('link-access').value;
      if (role !== 'keep') {
        const updated = await state.drive.setLinkAccess(state.file, role);
        // Sharing must not advance the loaded content version.
        state.file = { ...state.file, resourceKey: updated.resourceKey || state.file.resourceKey, capabilities: updated.capabilities };
      }
      const link = editorLink(location.href, state.folder, state.file); $('share-link').value = link; updateLink();
      try { await navigator.clipboard.writeText(link); shareModal.close(); say('Editor link copied.'); }
      catch { $('share-link').focus(); $('share-link').select(); say('Link ready. Copy the selected editor link.'); }
    } finally { $('apply-share-button').disabled = false; }
  });
};
let drag = null;
function pointerPoint(event) { const point = $('canvas').createSVGPoint(); point.x = event.clientX; point.y = event.clientY; return point.matrixTransform($('canvas').getScreenCTM().inverse()); }
$('canvas').onpointerdown = event => {
  if (event.button !== 0 || state.busy) return;
  const target = event.target.closest('[data-id]'); const point = pointerPoint(event);
  if (target) { state.selected = target.dataset.id; renderInspector(); renderControls(); if (!editable()) { renderCanvas(); return; }
    const node = state.map.graph.nodes.find(item => item.id === state.selected); drag = { node, x: node.x, y: node.y, start: point, remembered: false };
  } else drag = { pan: true, clientX: event.clientX, clientY: event.clientY, start: [...state.box] };
  $('canvas').setPointerCapture(event.pointerId);
};
$('canvas').onpointermove = event => {
  if (!drag) return;
  if (drag.pan) {
    const rect = $('canvas').getBoundingClientRect(); const scale = Math.max(drag.start[2]/rect.width, drag.start[3]/rect.height);
    state.box = [drag.start[0]-(event.clientX-drag.clientX)*scale, drag.start[1]-(event.clientY-drag.clientY)*scale, drag.start[2], drag.start[3]];
  } else {
    const point = pointerPoint(event), dx = point.x-drag.start.x, dy = point.y-drag.start.y;
    if (!drag.remembered && Math.abs(dx)+Math.abs(dy)>2) { remember(); drag.remembered = true; }
    if (drag.remembered) { drag.node.x = drag.x+dx; drag.node.y = drag.y+dy; changed(); }
  }
  renderCanvas();
};
$('canvas').onpointerup = $('canvas').onpointercancel = () => { drag = null; renderControls(); renderCanvas(); };
$('canvas').onwheel = event => { event.preventDefault(); zoom(event.deltaY>0 ? 1.1 : .9); };
window.addEventListener('pagehide', () => { if (state.dirty) checkpoint(); });
document.addEventListener('visibilitychange', () => { if (document.hidden && state.dirty) checkpoint(); });
window.addEventListener('beforeunload', event => { if (state.dirty) { event.preventDefault(); event.returnValue = ''; } });
if (document.modelContext?.registerTool) {
  const lifetime = new AbortController();
  try { Promise.resolve(document.modelContext.registerTool({ name: 'read_current_mind_map', title: 'Read current mind map',
    description: 'Read the currently open map and whether it is saved to Drive. Does not change or save it.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true, untrustedContentHint: true },
    execute(input) { if (!input || typeof input !== 'object' || Object.keys(input).length) throw new Error('No arguments expected.'); return { map: structuredClone(state.map), savedToDrive: !!state.file && !state.dirty, canEdit: editable() }; }
  }, { signal: lifetime.signal })).catch(() => {}); } catch { /* optional browser API */ }
  window.addEventListener('pagehide', () => lifetime.abort(), { once: true });
}
// Keep editing disabled until recovery is inspected, preventing an empty map
// from overwriting a stored draft during startup.
(async () => {
  state.busy = true; render();
  try {
    const recovered = await recovery?.read() || (storage ? new DraftRecovery(storage).read() : null);
    if (recovered?.dirty && confirm('Recover your unsaved map draft from this browser?')) {
      state.map = recovered.map; state.file = recovered.file; state.dirty = true; fit();
      say('Draft recovered. Connect Drive to save; concurrent edits will be checked.', false, true);
    }
  } catch { say('Local recovery could not be read. Keep a downloaded backup of your map.', true, true); }
  finally { state.busy = false; render(); }
})();
}
