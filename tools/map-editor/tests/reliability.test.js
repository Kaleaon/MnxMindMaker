import test from 'node:test';
import assert from 'node:assert/strict';
import { createMap, addNode, parseMap, serializeMap } from '../dist/graph.js';
import { DraftRecovery } from '../dist/recovery.js';
import { DriveClient, ConflictError } from '../dist/drive.js';
const storage = () => { const data = new Map(); return { getItem:k=>data.get(k) ?? null, setItem:(k,v)=>data.set(k,v), data }; };
const response = (value, headers={}) => new Response(typeof value==='string'?value:JSON.stringify(value), {headers});
test('cycles and non-native attribute values cannot be saved', () => {
 const m=createMap(), a=addNode(m), b=addNode(m); a.parent_id=b.id;b.parent_id=a.id;
 assert.throws(()=>serializeMap(m),/cycles/);b.parent_id=null;a.attributes.bad={nested:true};
 assert.throws(()=>serializeMap(m),/attributes/);a.attributes={};a.dimensions.bad='not a number';assert.throws(()=>serializeMap(m),/dimensions/);
});
test('long parent chains validate without recursive stack overflow',()=>{
 const m=createMap();for(let i=0;i<10000;i++){const n=addNode(m);n.parent_id=i?m.graph.nodes[i-1].id:null;}
 assert.equal(parseMap(serializeMap(m)).graph.nodes.length,10000);
});
test('draft recovery retains evidence and excludes access tokens',()=>{
 const s=storage(),r=new DraftRecovery(s),m=createMap();addNode(m).attributes.evidence_json='[{"source":"original"}]';
 r.write(m,{id:'f',version:'1',token:'secret',capabilities:{canEdit:true}},true);
 const result=r.read();assert.deepEqual(result.map,m);assert.equal(result.file.token,undefined);assert.equal(result.dirty,true);
 assert.ok(!s.getItem('mnx.draft.v1').includes('secret'));
});
test('corrupt newest draft falls back to previous valid draft',()=>{
 const s=storage(),r=new DraftRecovery(s),m=createMap('before');r.write(m,null,true);m.graph.name='after';r.write(m,null,true);
 s.setItem('mnx.draft.v1','broken');assert.equal(r.read().map.graph.name,'before');
});
test('quota failure is surfaced and does not erase the valid draft',()=>{
 const s=storage(),r=new DraftRecovery(s),m=createMap();r.write(m,null,true);s.setItem=()=>{throw new Error('quota');};
 assert.throws(()=>r.write(createMap('new'),null,true),/quota/);assert.equal(r.read().map.graph.name,m.graph.name);
});
test('missing conditional token blocks a content write',async()=>{
 let calls=0;const d=new DriveClient('t',async()=>{calls++;return response({version:'1',capabilities:{canEdit:true}});});
 await assert.rejects(d.saveMap({id:'f',version:'1'},createMap()),ConflictError);assert.equal(calls,1);
});
test('successful save is read back; mismatching saved content is rejected',async()=>{
 for(const mismatch of [false,true]){
 const m=createMap('draft'),calls=[];let written=false;
 const d=new DriveClient('t',async(url,options)=>{
 calls.push(options.method||'GET');if(options.method==='PATCH'){written=true;return response({id:'f',version:'2'});}
 if(url.includes('alt=media'))return response(serializeMap(mismatch?createMap('other'):m));
 return response({id:'f',version:written?'2':'1',capabilities:{canEdit:true}}, {ETag:'"token"'});
 });
 if(mismatch)await assert.rejects(d.saveMap({id:'f',version:'1'},m),/verified/);else assert.equal((await d.saveMap({id:'f',version:'1'},m)).version,'2');
 assert.equal(calls.filter(x=>x==='PATCH').length,1);assert.equal(calls.length,5);
 }
});
test('editing a verified statement resets verification and retains provenance',async()=>{
 const { reviseNode }=await import('../dist/graph.js');const n=addNode(createMap(),'[PROVEN] Old theorem');
 n.attributes={epistemic_schema_version:'1',verification_status:'established_in_literature',source_uri:'https://source.test',history_json:'[]'};
 reviseNode(n,{label:'Changed claim',description:'Different assumptions',type:'KNOWLEDGE'});
 assert.equal(n.attributes.verification_status,'unchecked');assert.equal(n.attributes.source_uri,'https://source.test');
 assert.equal(n.label,'[UNVERIFIED] Changed claim');assert.equal(JSON.parse(n.attributes.history_json)[0].previous.label,'[PROVEN] Old theorem');
});
