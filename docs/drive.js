import { MAX_BYTES, parseMap, serializeMap } from './graph.js';
const API = 'https://www.googleapis.com/drive/v3';
export const SCOPE = 'https://www.googleapis.com/auth/drive';
export class ConflictError extends Error {}
export class DriveClient {
  constructor(token, fetcher = (...args) => globalThis.fetch(...args)) { this.token = token; this.fetcher = fetcher; }
  async request(path, options = {}, resources = []) {
    const headers = new Headers(options.headers);
    headers.set('Authorization', 'Bearer ' + this.token);
    const keys = resources.filter(resource => resource?.resourceKey).map(resource => `${resource.id}/${resource.resourceKey}`);
    if (keys.length) headers.set('X-Goog-Drive-Resource-Keys', keys.join(','));
    const response = await this.fetcher(path.startsWith('https://www.googleapis.com/') ? path : API + path, { ...options, headers });
    if (!response.ok) {
      if (response.status === 412) throw new ConflictError('Someone changed this map. Download your draft, then reload the Drive version.');
      if (response.status === 401) throw new Error('Your Google session expired. Connect to Drive again; your draft is still here.');
      let message;
      try { message = (await response.json()).error?.message; } catch { /* no provider JSON */ }
      throw new Error(message || `Google Drive returned ${response.status}. Check your access and try again.`);
    }
    return response;
  }
  async metadata(file) {
    const fields = 'id,name,mimeType,version,modifiedTime,resourceKey,webViewLink,capabilities(canEdit,canShare,canAddChildren),size';
    const response = await this.request(`/files/${encodeURIComponent(file.id)}?supportsAllDrives=true&fields=${encodeURIComponent(fields)}`, {}, [file]);
    return { ...await response.json(), etag: response.headers.get('etag') };
  }
  async listFolder(folder) {
    const metadata = await this.metadata(folder);
    if (metadata.mimeType !== 'application/vnd.google-apps.folder') throw new Error('This link is not a Drive folder.');
    const files = [];
    let pageToken;
    do {
      const params = new URLSearchParams({ q: `'${folder.id}' in parents and trashed = false`,
        fields: 'nextPageToken,files(id,name,mimeType,resourceKey,modifiedTime,capabilities(canEdit))',
        pageSize: '100', orderBy: 'name', supportsAllDrives: 'true', includeItemsFromAllDrives: 'true' });
      if (pageToken) params.set('pageToken', pageToken);
      const response = await this.request('/files?' + params, {}, [folder]);
      const page = await response.json();
      files.push(...page.files.filter(file => /\.mnxj$/i.test(file.name)));
      pageToken = page.nextPageToken;
    } while (pageToken);
    return { folder: { ...metadata, resourceKey: metadata.resourceKey || folder.resourceKey }, files };
  }
  async readMap(file) {
    // A before/after version check prevents combining content and metadata from
    // different revisions while another editor is saving.
    const before = await this.metadata(file);
    if (Number(before.size) > MAX_BYTES) throw new Error('Maps must be smaller than 10 MiB.');
    const response = await this.request(`/files/${encodeURIComponent(file.id)}?alt=media&supportsAllDrives=true`, {}, [file]);
    const length = Number(response.headers.get('content-length'));
    if (length > MAX_BYTES) throw new Error('Maps must be smaller than 10 MiB.');
    const map = parseMap(await response.text());
    const after = await this.metadata(file);
    if (before.version !== after.version) throw new ConflictError('This map changed while opening. Try opening it again.');
    return { map, file: { ...after, resourceKey: after.resourceKey || file.resourceKey } };
  }
  async saveMap(file, map) {
    const current = await this.metadata(file);
    if (!current.capabilities?.canEdit) throw new Error('You have view access. Ask the folder owner for edit access.');
    if (current.version !== file.version) throw new ConflictError('Someone changed this map. Download your draft, then reload the Drive version.');
    const headers = { 'Content-Type': 'application/json; charset=utf-8' };
    if (current.etag) headers['If-Match'] = current.etag;
    const body = serializeMap(map);
    if (new TextEncoder().encode(body).length > MAX_BYTES) throw new Error('Maps must be smaller than 10 MiB.');
    const response = await this.request(`https://www.googleapis.com/upload/drive/v3/files/${encodeURIComponent(file.id)}?uploadType=media&supportsAllDrives=true&fields=id,name,version,resourceKey,capabilities(canEdit,canShare)`, { method: 'PATCH', headers, body }, [file]);
    return { ...file, ...await response.json() };
  }
  async createMap(folder, map) {
    if (!folder?.capabilities?.canAddChildren) throw new Error('You cannot add maps to this folder. Ask its owner for edit access.');
    const boundary = 'mnx_' + crypto.randomUUID();
    const body = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify({ name: map.graph.name + '.mnxj', mimeType: 'application/json', parents: [folder.id] })}\r\n--${boundary}\r\nContent-Type: application/json\r\n\r\n${serializeMap(map)}\r\n--${boundary}--`;
    if (new TextEncoder().encode(body).length > MAX_BYTES) throw new Error('Maps must be smaller than 10 MiB.');
    const response = await this.request('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true&fields=id,name,version,resourceKey,capabilities(canEdit,canShare)', { method: 'POST', headers: { 'Content-Type': 'multipart/related; boundary=' + boundary }, body }, [folder]);
    return response.json();
  }
  async setLinkAccess(file, role) {
    if (!['reader', 'writer'].includes(role)) throw new Error('Invalid sharing role.');
    const metadata = await this.metadata(file);
    if (!metadata.capabilities?.canShare) throw new Error('Only a user with sharing permission can change link access.');
    const response = await this.request(`/files/${encodeURIComponent(file.id)}/permissions?supportsAllDrives=true&fields=permissions(id,type,role,permissionDetails)`, {}, [file]);
    const permissions = (await response.json()).permissions;
    const anyone = permissions.find(permission => permission.type === 'anyone');
    if (anyone?.permissionDetails?.some(detail => detail.inherited)) throw new Error('Link access is inherited from the folder. Change it in Google Drive.');
    if (anyone?.role === role) return metadata;
    const path = `/files/${encodeURIComponent(file.id)}/permissions` + (anyone ? '/' + encodeURIComponent(anyone.id) : '') + '?supportsAllDrives=true';
    await this.request(path, { method: anyone ? 'PATCH' : 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(anyone ? { role } : { type: 'anyone', role, allowFileDiscovery: false }) }, [file]);
    return this.metadata(file);
  }
}

export function connectGoogle(clientId) {
  if (!/^[\w.-]+\.apps\.googleusercontent\.com$/.test(clientId)) throw new Error('Add the Google OAuth web client ID in Settings first.');
  if (!globalThis.google?.accounts?.oauth2) throw new Error('Google sign-in is still loading or is blocked. Try again.');
  return new Promise((resolve, reject) => {
    const client = google.accounts.oauth2.initTokenClient({ client_id: clientId, scope: SCOPE,
      callback: result => {
        if (result.error) reject(new Error(result.error_description || result.error));
        else if (!result.access_token || !google.accounts.oauth2.hasGrantedAllScopes(result, SCOPE)) reject(new Error('Google Drive permission was not granted. Connect again and allow Drive access.'));
        else resolve(result);
      },
      error_callback: error => reject(new Error(error.type === 'popup_closed' ? 'Google sign-in was cancelled.' : 'Allow the Google sign-in popup, then try again.')) });
    client.requestAccessToken({ prompt: '' });
  });
}
