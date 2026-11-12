import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile,mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {PGlite} from '@electric-sql/pglite';
import sharp from 'sharp';
import WebSocket from 'ws';
import {CommunityStore} from '../src/learning-store.mjs';
import {createReferenceGateway} from '../src/v2/gateway.mjs';
import {createCommunityServer} from '../src/server.mjs';
import {listen} from './support.mjs';

test('reference 2.0: canonical posts, rich media, wallet, room ACL, live drawing and voice', {timeout:90000}, async t=>{
 const dir=await mkdtemp(join(tmpdir(),'artlive-reference-'));
 let pg,baseDatabase;
 if(process.env.COMMUNITY_REFERENCE_TEST_CONFIG){
  const config=JSON.parse(await readFile(process.env.COMMUNITY_REFERENCE_TEST_CONFIG,'utf8')),url=new URL(config.runtimeUrl);
  assert.equal(url.hostname,'127.0.0.1');assert.match(url.pathname,/^\/community_reference_fixture_[a-z0-9_]+$/);assert.equal(config.admin.database,url.pathname.slice(1));assert.match(url.username,/^community_reference_runtime_/);
  const driver=await import('pg'),pool=new driver.default.Pool({...config.admin,max:2});pg={query:(...args)=>pool.query(...args),close:()=>pool.end()};
  const {postgresDatabase}=await import('../src/database.mjs');baseDatabase=postgresDatabase(config.runtimeUrl);await baseDatabase.verifyRuntimeRole();
  assert.equal((await pg.query('SELECT count(*)::int AS n FROM community.posts')).rows[0].n,0,'fresh fixture only; refuse business data');
 }else{
  pg=new PGlite();
  for(const name of ['001_foundation','002_learning_community','003_reference_community'])await pg.exec(await readFile(new URL('../db/'+name+'.sql',import.meta.url),'utf8'));
  baseDatabase={query:(...args)=>pg.query(...args),transaction:work=>pg.transaction(work)};
 }
 let failMessageCommit=false,losePostCommit=false;
 const database={...baseDatabase,transaction:async work=>{
  let message=false,post=false;
  const result=await baseDatabase.transaction(async client=>{
   const result=await work({query(sql,values){if(/^INSERT INTO community\.v2_room_messages/i.test(sql))message=true;if(/^INSERT INTO community\.v2_posts/i.test(sql))post=true;return client.query(sql,values);}});
   if(failMessageCommit&&message){failMessageCommit=false;throw new Error('INJECTED_ROLLBACK_BEFORE_COMMIT');}return result;
  });
  if(losePostCommit&&post){losePostCommit=false;throw new Error('INJECTED_LOST_COMMIT_REPLY');}return result;
 }};
 const store=new CommunityStore(database);assert.equal(await store.ready(),true);
 const core={async communityIdentity(token){const id=Number(token);if(!Number.isSafeInteger(id)||id<1)throw Object.assign(new Error('invalid token'),{status:401});return{id,username:'fixture_'+id,displayName:'观画人'+id,email:id+'@example.invalid',admin:id===404};},async authenticate(token){return{id:token,displayName:'观画人'+token};}};
 const origin='http://localhost:3000';
 const providerCalls=[];
 const critique='画面以疏朗的水面留白衬托远山的层次，近景浓墨和远景淡墨形成清晰的空间关系。选区中的树石沿着斜向走势引导视线，让山势与水路自然衔接。这里可以关注墨色边缘的虚实变化，并比较枝干线条和山石皴擦的轻重，体会画面在疏密之间保持的节奏。目前仅能依据可见笔墨讨论构图与意境，无法据此判断作者年代或作品真伪。';
 const providerFetch=async(url,options={})=>{
  if(!String(url).startsWith('https://api.deepseek.com/'))return Response.json({query:{search:[]}});
  const body=JSON.parse(options.body);providerCalls.push(body);
  const vision=Array.isArray(body.messages.at(-1).content);
  const text=vision?JSON.stringify({theme:'烟岚山水',composition:'近实远虚',technique:'浓淡相生',keywords:['山水'],critique,selectedRegionDescription:'树石与留白'}):'山色入砚池，云心落纸迟。这是即兴拟作。';
  return Response.json({model:body.model,choices:[{message:{content:text}}]});
 };
 const gateway=await createReferenceGateway({database,core,mediaRoot:dir,allowedOrigin:origin,providerFetch});
 const server=createCommunityServer({store,core,reference:gateway,allowedOrigin:origin});
 const base=await listen(server);const sockets=[];
 t.after(async()=>{server.closeAllConnections();await server.drain();await baseDatabase.close?.();await pg.close();await rm(dir,{recursive:true,force:true});});
 async function request(actor,path,{method='GET',body,requestId=randomUUID(),headers={}}={}){
  const binary=Buffer.isBuffer(body);
  const response=await fetch(base+'/api/v2/community'+path,{method,headers:{authorization:'Bearer '+actor,'x-request-id':requestId,...(body!==undefined?{'content-type':binary?'image/png':'application/json'}:{}),...headers},body:body===undefined?undefined:binary?body:JSON.stringify(body)});
  const type=response.headers.get('content-type')||'';return {status:response.status,data:type.includes('application/json')?await response.json():await response.arrayBuffer(),headers:response.headers};
 }
 const ok=async(actor,path,options)=>{const response=await request(actor,path,options);assert(response.status<400,JSON.stringify({path,...response}));return response.data.data;};
 for(const actor of [101,202,303,404,505,606,707,808,909])await ok(actor,'/api/user/profile');
 await t.test('retired poet and administration endpoints cannot read, write or call a provider',async()=>{
  const before=await pg.query('SELECT (SELECT count(*) FROM community.v2_ai_messages) AS messages,(SELECT count(*) FROM community.v2_posts) AS posts,(SELECT count(*) FROM community.v2_operations) AS operations');
  const calls=providerCalls.length;
  for(const [method,path,body] of [
   ['GET','/api/ai/agents'],['GET','/api/ai/agents/1/messages'],
   ['POST','/api/ai/test',{}],['POST','/api/ai/agents/1/chat',{message:'写一首诗'}],
   ['GET','/api/admin/ai/settings'],['PUT','/api/admin/ai/settings',{holidayTheme:'春日'}],
   ['PUT','/api/admin/ai/agents/1',{persona:'fixture'}],['POST','/api/admin/ai/publish',{content:'不应发布'}],
  ])assert.equal((await request(404,path,{method,body})).status,404,path);
  assert.equal(providerCalls.length,calls);
  assert.deepEqual((await pg.query('SELECT (SELECT count(*) FROM community.v2_ai_messages) AS messages,(SELECT count(*) FROM community.v2_posts) AS posts,(SELECT count(*) FROM community.v2_operations) AS operations')).rows,before.rows);
  const capabilities=await fetch(base+'/api/v1/community/capabilities',{headers:{authorization:'Bearer 101'}}).then(response=>response.json());
  assert.equal(capabilities.ai,false);assert.equal(capabilities.aiCritique,true);
 });
 let post;
 await t.test('pure media, tags, canonical UUID bridge, comments and quote snapshots',async()=>{
  const png=await sharp({create:{width:240,height:180,channels:3,background:'#f6f0e5'}}).png().toBuffer();
  const upload=await ok(101,'/api/uploads?type=image',{method:'POST',body:png});
  post=await ok(101,'/api/posts',{method:'POST',body:{content:'',images:[upload.url],tags:['山水','写意']}});
  assert(post.id>0);assert.deepEqual(post.tags,['写意','山水']);
  const canonical=await pg.query('SELECT * FROM community.posts WHERE id=(SELECT legacy_id FROM community.v2_posts WHERE id=$1)',[post.id]);assert.equal(canonical.rows[0].content,'');
  const liked=await ok(202,`/api/posts/${post.id}/like`,{method:'POST',body:{}});assert.equal(liked.liked,true);
  assert.equal((await pg.query("SELECT count(*)::int AS n FROM community.reactions WHERE kind='like'")).rows[0].n,1);
  const comment=await ok(202,`/api/posts/${post.id}/comments`,{method:'POST',body:{content:'留白形成水面的呼吸。'}});
  const legacyComment=(await pg.query('SELECT legacy_id FROM community.v2_comments WHERE id=$1',[comment.id])).rows[0].legacy_id;
  await ok(101,`/api/comments/${comment.id}/like`,{method:'POST',body:{}});
  const quote=await ok(202,'/api/posts',{method:'POST',body:{content:'我更关注这里的水面节奏。',quotedPostId:post.id}});assert.equal(quote.quote.available,true);
  await ok(101,`/api/posts/${post.id}`,{method:'PUT',body:{content:'已更新的画作说明',images:[upload.url],tags:['山水']}});
  assert.equal((await ok(202,`/api/posts/${quote.id}`)).post.quote.edited,true);
  assert.equal((await request(303,`/api/comments/${comment.id}`,{method:'DELETE',body:{}})).status,403);
  await ok(202,`/api/comments/${comment.id}`,{method:'DELETE',body:{}});
  assert.equal((await pg.query('SELECT reference_deleted FROM community.comments WHERE id=$1',[legacyComment])).rows[0].reference_deleted,true);
  assert.equal((await fetch(base+'/api/v1/community/comments/'+legacyComment,{headers:{authorization:'Bearer 303'}})).status,404);
  assert.equal((await ok(101,'/api/legacy-posts/'+post.legacyId)).id,post.id);
  const legacyPost=await fetch(base+'/api/v1/community/posts/'+post.legacyId,{headers:{authorization:'Bearer 303'}});assert.equal(legacyPost.status,200);assert.equal((await legacyPost.json()).images.length,1);
  const caseContent='CASE_SEARCH_'+randomUUID().toUpperCase();
  const casePost=await ok(101,'/api/posts',{method:'POST',body:{content:caseContent}});
  const caseSearch=await ok(303,'/api/posts?q='+encodeURIComponent(caseContent.toLowerCase()));
  assert.deepEqual(caseSearch.items.map(item=>item.id),[casePost.id]);
 });
 await t.test('expert-only tickets, initial-gift restriction, single charge, refunds and seven-day settlement',async()=>{
  const body={name:'专家付费共赏',visibility:'public',entryPrice:1};
  assert.equal((await request(101,'/api/v2/rooms',{method:'POST',body})).status,403);
  await ok(404,'/api/admin/users/101/expert',{method:'PATCH',body:{verified:true}});
  const paid=await ok(101,'/api/v2/rooms',{method:'POST',body});
  const joinPath=`/api/v2/rooms/${paid.id}/join`,payment={acceptPrice:true,expectedPrice:1};
  assert.equal((await request(303,joinPath,{method:'POST',body:payment})).status,409);
  await ok(303,'/api/coins/tasks/daily_login/claim',{method:'POST',body:{}});
  await ok(303,joinPath,{method:'POST',body:payment});await ok(303,joinPath,{method:'POST',body:payment});
  assert.equal((await ok(303,'/api/coins/wallet')).balance,10);
  assert.equal((await ok(101,'/api/coins/room-income')).pending,1);
  await ok(101,`/api/v2/rooms/${paid.id}/members/303`,{method:'DELETE',body:{}});
  assert.equal((await ok(303,'/api/coins/wallet')).balance,11);
  await ok(505,'/api/coins/tasks/daily_login/claim',{method:'POST',body:{}});
  await ok(505,joinPath,{method:'POST',body:payment});
  await pg.query("UPDATE community.v2_room_entry_payments SET settle_after='2000-01-01T00:00:00.000Z' WHERE room_id=$1 AND status='pending'",[paid.id]);
  assert.equal((await ok(101,'/api/coins/room-income')).settled,1);
  const payments=(await ok(404,'/api/admin/room-payments')).items;
  const settled=payments.find(value=>value.roomId===paid.id&&value.payerId===505);assert.equal(settled.status,'settled');
  await ok(404,`/api/admin/room-payments/${settled.id}/refund`,{method:'POST',body:{}});
  assert.equal((await ok(505,'/api/coins/wallet')).balance,11);
  assert.equal((await ok(101,'/api/coins/wallet')).balance,10);
 });
 await t.test('one journal outcome; task caps and coin contribution cap',async()=>{
  const requestId=randomUUID();const command={method:'POST',body:{content:'唯一提交'},requestId};
  const one=await ok(202,'/api/posts',command);const two=await ok(202,'/api/posts',command);assert.equal(one.id,two.id);
  assert.equal((await ok(202,'/api/operations/'+requestId)).state,'COMMITTED');
  losePostCommit=true;const lostId=randomUUID(),lost={method:'POST',body:{content:'丢失提交响应仍只能产生一帖'},requestId:lostId};
  const committed=await ok(303,'/api/posts',lost),replay=await ok(303,'/api/posts',lost);assert.equal(committed.id,replay.id);
  assert.equal((await pg.query('SELECT count(*)::int AS n FROM community.posts WHERE content=$1',['丢失提交响应仍只能产生一帖'])).rows[0].n,1);
  assert.equal((await ok(303,'/api/operations/'+lostId)).state,'COMMITTED');
  await ok(202,'/api/coins/tasks/daily_login/claim',{method:'POST',body:{}});
  assert.equal((await request(202,'/api/coins/tasks/daily_login/claim',{method:'POST',body:{}})).status,409);
  await ok(202,`/api/posts/${post.id}/coins`,{method:'POST',body:{amount:3,idempotencyKey:randomUUID()}});
  assert.equal((await request(202,`/api/posts/${post.id}/coins`,{method:'POST',body:{amount:3,idempotencyKey:randomUUID()}})).status,409);
  assert.equal((await request(101,`/api/posts/${post.id}/coins`,{method:'POST',body:{amount:1,idempotencyKey:randomUUID()}})).status,403);
 });
 let room,session,asset;
 await t.test('private room membership, upload, annotation and version conflicts',async()=>{
  room=await ok(101,'/api/v2/rooms',{method:'POST',body:{name:'烟岚共赏',visibility:'public',memberLimit:10}});
  await ok(202,`/api/v2/rooms/${room.id}/join`,{method:'POST',body:{}});
  await ok(101,`/api/v2/rooms/${room.id}/members/202/role`,{method:'PATCH',body:{role:'member'}});
  const image=await sharp({create:{width:800,height:600,channels:3,background:'#e8dfcf'}}).png().toBuffer();
  asset=await ok(101,'/api/v2/artwork-assets',{method:'POST',body:image});
  session=await ok(101,`/api/v2/rooms/${room.id}/artwork-sessions`,{method:'POST',body:{assetId:asset.id,title:'山水',visitorVisible:false}});
  assert.equal((await request(303,asset.originalUrl)).status,404);
  assert.equal((await request(202,asset.originalUrl)).status,200);
  const geometry={x:.1,y:.1,width:.2,height:.2};
  const annotation=await ok(202,`/api/v2/rooms/${room.id}/artwork-sessions/${session.id}/annotations`,{method:'POST',body:{shapeType:'rectangle',geometry,content:'远山层次清晰，水面留白可以再疏朗一些。'}});
  assert.equal(annotation.version,1);
  assert.equal((await request(202,`/api/v2/annotations/${annotation.id}`,{method:'PATCH',body:{version:0,content:'过期编辑'}})).status,409);
 });
 await t.test('room critique keeps explicit image consent, editable AI drafts and no stored credentials',async()=>{
  const headers={'x-deepseek-key':'sk-fixture-secret-never-store','x-deepseek-model':'fixture-vision'};
  const path=`/api/v2/rooms/${room.id}/artwork-sessions/${session.id}/ai-critiques`;
  const geometry={x:.1,y:.1,width:.3,height:.3};
  const before=providerCalls.length;
  assert.equal((await request(202,path,{method:'POST',body:{shapeType:'rectangle',geometry,consent:false},headers})).status,400);
  assert.equal(providerCalls.length,before);
  const draft=await ok(202,path,{method:'POST',body:{shapeType:'rectangle',geometry,consent:true},headers});assert(draft.characterCount>=100&&draft.characterCount<=500);
  const annotation=await ok(202,`/api/v2/rooms/${room.id}/artwork-sessions/${session.id}/annotations`,{method:'POST',body:{shapeType:'rectangle',geometry,content:draft.content+'（经本人校订）',aiDraftId:draft.id}});
  assert.equal(annotation.sourceType,'ai_assisted');assert.equal(annotation.aiMetadata.draftId,draft.id);
  assert.equal(JSON.stringify((await pg.query('SELECT * FROM community.v2_ai_annotation_drafts')).rows).includes('sk-fixture-secret'),false);
 });
 async function socket(actor){
  const ticket=(await ok(actor,'/api/ws-ticket',{method:'POST',body:{}})).ticket;
  const ws=new WebSocket(base.replace(/^http/,'ws')+'/ws?ticket='+ticket,{origin});sockets.push(ws);
  ws.events=[];ws.on('message',raw=>ws.events.push(JSON.parse(raw)));
  await new Promise((resolve,reject)=>{ws.on('open',resolve);ws.on('error',reject);});
  ws.send(JSON.stringify({type:'room.subscribe',roomId:room.id}));
  await event(ws,'room.subscribed');return ws;
 }
 async function event(ws,type){const until=Date.now()+5000;while(Date.now()<until){const index=ws.events.findIndex(value=>value.eventType===type);if(index>=0)return ws.events.splice(index,1)[0];await new Promise(r=>setTimeout(r,10));}throw new Error('Missing event '+type);}
 await t.test('real WebSocket subscription, chat, shared view and voice permissions',async()=>{
  const owner=await socket(101),member=await socket(202);
  await ok(202,`/api/v2/rooms/${room.id}/messages`,{method:'POST',body:{content:'同看一幅画。'}});
  assert.equal((await event(owner,'chat.message.created')).data.content,'同看一幅画。');
  failMessageCommit=true;
  assert.equal((await request(101,`/api/v2/rooms/${room.id}/messages`,{method:'POST',body:{content:'回滚消息不能广播'}})).status,503);
  assert.equal((await pg.query('SELECT count(*)::int AS n FROM community.v2_room_messages WHERE content=$1',['回滚消息不能广播'])).rows[0].n,0);
  assert.equal(owner.events.some(value=>value.data?.content==='回滚消息不能广播'),false);
  await ok(101,`/api/v2/rooms/${room.id}/share/start`,{method:'POST',body:{}});
  owner.send(JSON.stringify({type:'share.viewport',viewport:{scale:2,panX:.1,panY:.1}}));
  assert.equal((await event(member,'share.viewport')).data.scale,2);
  owner.send(JSON.stringify({type:'voice.join'}));await event(owner,'voice.roster');
  owner.send(JSON.stringify({type:'voice.start'}));assert.equal((await event(owner,'voice.updated')).data.speaking,true);
  await ok(101,`/api/v2/rooms/${room.id}/members/202/role`,{method:'PATCH',body:{role:'visitor'}});
  member.send(JSON.stringify({type:'voice.join'}));await event(member,'voice.roster');member.send(JSON.stringify({type:'voice.start'}));await event(member,'voice.error');
  await ok(101,`/api/v2/rooms/${room.id}/share/stop`,{method:'POST',body:{}});
  const closed=new Promise(resolve=>member.once('close',resolve));
  await ok(404,'/api/admin/users/202/status',{method:'PATCH',body:{status:1}});await closed;
  const bannedPost=(await pg.query('SELECT legacy_id FROM community.v2_posts WHERE user_id=202 ORDER BY id LIMIT 1')).rows[0].legacy_id;
  assert.equal((await fetch(base+'/api/v1/community/posts/'+bannedPost,{headers:{authorization:'Bearer 303'}})).status,404);
  assert.equal((await fetch(base+'/api/v1/community/capabilities',{headers:{authorization:'Bearer 202'}})).status,403);
 });
});
