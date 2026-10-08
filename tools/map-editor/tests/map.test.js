import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { addNode, createMap, editorLink, parseDriveLink, parseMap, removeNode, serializeMap } from '../dist/graph.js';
import { ConflictError, DriveClient } from '../dist/drive.js';
const fixture = () => parseMap(readFileSync(new URL('../../../app/src/test/resources/knowledge-core-seed.mnxj', import.meta.url), 'utf8'));
const response = (value, status = 200, headers = {}) => new Response(typeof value === 'string' ? value : JSON.stringify(value), { status, headers });
test('evidence, dimensions and unknown properties survive edits', () => {
 const map=fixture(), before=structuredClone(map); map.custom_future={preserved:true}; map.graph.nodes[0].label='Edited';
 const restored=parseMap(serializeMap(map));
 assert.deepEqual(restored.graph.nodes[0].attributes,before.graph.nodes[0].attributes);
 assert.deepEqual(restored.graph.nodes[0].dimensions,before.graph.nodes[0].dimensions);
 assert.deepEqual(restored.graph.edges,before.graph.edges); assert.equal(restored.custom_future.preserved,true);
});
test('node deletion removes dangling references', () => {
 const map=createMap(),parent=addNode(map,'Parent'),child=addNode(map,'Child'); child.parent_id=parent.id;
 map.graph.edges.push({id:'edge',from_node_id:parent.id,to_node_id:child.id,label:'includes',strength:1});
 removeNode(map,parent.id); assert.equal(map.graph.edges.length,0); assert.equal(child.parent_id,null); parseMap(serializeMap(map));
});
test('Drive links reject lookalike domains and preserve resource keys', () => {
 assert.deepEqual(parseDriveLink('https://drive.google.com/drive/u/0/folders/folder_123?resourcekey=key'),{id:'folder_123',resourceKey:'key'});
 assert.throws(()=>parseDriveLink('https://drive.google.com.evil.test/drive/folders/folder'));
 const url=new URL(editorLink('https://example.test/repo/?old=1#old',{id:'folder'},{id:'file',resourceKey:'key'}));
 assert.equal(url.pathname,'/repo/'); assert.equal(url.search,''); assert.equal(new URLSearchParams(url.hash.slice(1)).get('filekey'),'key');
});
test('invalid schemas, duplicate IDs, broken references and oversized maps fail', () => {
 const duplicate=fixture(); duplicate.graph.nodes.push(structuredClone(duplicate.graph.nodes[0])); assert.throws(()=>parseMap(JSON.stringify(duplicate)),/Duplicate/);
 const newer=fixture(); newer.schema.version.major=2; assert.throws(()=>parseMap(JSON.stringify(newer)),/version 1/);
 const broken=fixture(); broken.graph.edges[0].to_node_id='missing'; assert.throws(()=>parseMap(JSON.stringify(broken)),/missing node/);
 assert.throws(()=>parseMap(' '.repeat(64*1024*1024+1)),/64 MiB/);
});
test('folder listing follows pagination and sends resource-key and token headers', async () => {
 const calls=[]; const drive=new DriveClient('token',async(url,options)=>{
  calls.push({url,options}); if(url.includes('/files/folder?')) return response({id:'folder',mimeType:'application/vnd.google-apps.folder'});
  if(url.includes('pageToken=next')) return response({files:[{id:'two',name:'second.mnxj'}]});
  return response({nextPageToken:'next',files:[{id:'one',name:'first.mnxj'},{id:'ignore',name:'other.pdf'}]});
 });
 const result=await drive.listFolder({id:'folder',resourceKey:'key'}); assert.equal(result.files.length,2);
 assert.equal(calls[0].options.headers.get('Authorization'),'Bearer token'); assert.equal(calls[1].options.headers.get('X-Goog-Drive-Resource-Keys'),'folder/key');
});
test('viewers and stale drafts cannot submit content writes', async () => {
 for(const [version,canEdit,expected] of [['1',false,/view access/],['2',true,ConflictError]]){
  let calls=0; const drive=new DriveClient('token',async()=>{calls++;return response({version,capabilities:{canEdit}});});
  await assert.rejects(drive.saveMap({id:'file',version:'1'},fixture()),expected); assert.equal(calls,1);
 }
});
test('save preserves data, sends If-Match and never retries rejected writes', async () => {
 const calls=[]; const drive=new DriveClient('token',async(url,options)=>{
  calls.push({url,options}); return options.method==='PATCH'?response({error:{message:'Conflict'}},412):response({version:'1',capabilities:{canEdit:true}},200,{ETag:'"v1"'});
 });
 const map=fixture();map.extra='retained'; await assert.rejects(drive.saveMap({id:'file',version:'1'},map),ConflictError);
 assert.equal(calls.length,2);assert.equal(calls[1].options.headers.get('If-Match'),'"v1"'); assert.equal(JSON.parse(calls[1].options.body).extra,'retained');
});
test('changed content during load is rejected', async () => {
 let count=0; const drive=new DriveClient('token',async url=>url.includes('alt=media')?response(JSON.stringify(fixture())):response({version:String(++count)}));
 await assert.rejects(drive.readMap({id:'file'}),ConflictError);
});
test('sharing requires canShare and changes only the anyone permission', async () => {
 const calls=[];const drive=new DriveClient('token',async(url,options)=>{
  calls.push({url,options});if(options.method==='PATCH') return response({id:'anyone'});
  if(url.includes('/permissions?'))return response({permissions:[{id:'owner',type:'user',role:'owner'},{id:'anyone',type:'anyone',role:'reader'}]});
  return response({capabilities:{canShare:true}});
 });
 await drive.setLinkAccess({id:'file'},'writer');const writes=calls.filter(call=>call.options.method==='PATCH');assert.equal(writes.length,1);
 assert.match(writes[0].url,/permissions\/anyone\?/);assert.equal(JSON.parse(writes[0].options.body).role,'writer');
 const denied=new DriveClient('token',async()=>response({capabilities:{canShare:false}})); await assert.rejects(denied.setLinkAccess({id:'file'},'writer'),/sharing permission/);
});
