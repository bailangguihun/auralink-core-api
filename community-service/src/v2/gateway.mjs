import {randomBytes,createHash,randomUUID} from 'node:crypto';
import {Readable} from 'node:stream';
import {EventEmitter} from 'node:events';
import fs from 'node:fs';
import path from 'node:path';
import sharp from 'sharp';
import {referenceDatabase} from './database.mjs';
import {createReferenceCommunity} from './posts.mjs';
import {Failure} from '../errors.mjs';
import {serveAuthorizedFile} from '../media.mjs';

const PREFIX='/api/v2/community';
const opaque=()=>randomBytes(32).toString('base64url');
const uuid=value=>/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value||'');
function json(res,status,message,data=null){res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'private, no-store','x-content-type-options':'nosniff'});res.end(JSON.stringify({code:status<400?200:status,message,data}));}
function capture(){const result=new EventEmitter();Object.assign(result,{statusCode:200,headers:{},chunks:[],setHeader(k,v){this.headers[k.toLowerCase()]=v;},writeHead(status,headers={}){this.statusCode=status;Object.assign(this.headers,headers);},write(chunk){this.chunks.push(Buffer.from(chunk));return true;},end(chunk){if(chunk)this.write(chunk);this.finished=true;}});return result;}
function flush(res,record){res.writeHead(record.statusCode,{...record.headers,'cache-control':'private, no-store','x-content-type-options':'nosniff'});res.end(Buffer.concat(record.chunks));}
const failureRecord=error=>({statusCode:error.status||503,headers:{'content-type':'application/json'},chunks:[Buffer.from(JSON.stringify({code:error.status||503,message:error.status&&error.status<500?error.message:'请求结果尚未确认，请读取操作记录，不要重复提交。'}))]});

/** Core remains the identity authority. Grants and socket tickets never contain a JWT. */
export async function createReferenceGateway({database,core,mediaRoot,allowedOrigin,providerFetch}){
 const db=referenceDatabase(database), grants=new Map(), tickets=new Map(), credentials=new Map();
 const uploadDir=path.join(mediaRoot,'reference');fs.mkdirSync(uploadDir,{recursive:true,mode:0o700});
 const prune=map=>{for(const [key,value]of map)if(value.expires<Date.now())map.delete(key);};
 function mediaGrant(res,token){prune(grants);const grant=opaque();grants.set(grant,{token,expires:Date.now()+20*60*1000});res.setHeader('set-cookie',`artlive_community_media=${grant}; HttpOnly; SameSite=Strict; Path=/api/community/v2; Max-Age=1200${allowedOrigin.startsWith('https:')?'; Secure':''}`);}
 async function actorFor(token,fresh=true){
  let entry=credentials.get(token);
  if(fresh||!entry||entry.expires<Date.now()){
   const identity=await core.communityIdentity(token);
   entry={identity,expires:Date.now()+15000};credentials.set(token,entry);prune(credentials);
  }
  return entry.identity;
 }
 async function identityFor(req){
  if(req.communityUser)return req.communityUser;
  const token=(req.headers.authorization||'').replace(/^Bearer /,'');
  if(!token)return null;
  let actor;
  try{actor=await actorFor(token,false);}catch(error){if([401,403].includes(error.status))return null;throw error;}
  return db.prepare('SELECT * FROM users WHERE id=? AND status=0').get(actor.id);
 }
 async function consumeTicket(ticket,origin){
  prune(tickets);const value=tickets.get(ticket);tickets.delete(ticket);
  if(!value||origin!==allowedOrigin)return '';
  try{await actorFor(value.token,true);return value.token;}catch{return '';}
 }
 const pending=new Set();let wss;
 const socketWork=work=>{const promise=db.unit(work).catch(error=>{console.error(JSON.stringify({event:'community_socket_command_failed',code:error.code||'INTERNAL_ERROR'}));}).finally(()=>pending.delete(promise));pending.add(promise);return promise;};
 const reference=await db.unit(()=>createReferenceCommunity({db,uploadDir,identityFor,consumeTicket,socketWork,providerFetch}));
 let activeAI=0;
 async function syncIdentity(actor){
  await db.query(`INSERT INTO community.v2_users(id,username,nickname,email,role) VALUES($1,$2,$3,$4,$5)
   ON CONFLICT(id) DO UPDATE SET username=excluded.username,role=excluded.role`,[actor.id,actor.username,actor.displayName,actor.email,actor.admin?'admin':'user']);
  const user=await db.prepare('SELECT * FROM users WHERE id=?').get(actor.id);
  if(!user||user.status!==0)throw new Failure(403,'COMMUNITY_ACCOUNT_DISABLED','账号已被禁用');
  return user;
 }
 async function serveUpload(req,res,filename,user){
  if(!/^[-\w.]+$/.test(filename)||filename==='.'||filename==='..')return json(res,404,'文件不存在');
  const relative='/uploads/'+filename;
  const asset=await db.prepare('SELECT * FROM artwork_assets WHERE original_url=? OR url=? OR preview_url=? OR thumbnail_url=?').get(relative,relative,relative,relative);
  let permitted=false;
  const publiclyPosted=async()=>Boolean(await db.prepare(`SELECT 1 FROM posts p JOIN users u ON u.id=p.user_id WHERE p.status=0 AND u.status=0 AND
     (p.images LIKE ? OR p.audio=? OR p.video=?) LIMIT 1`).get('%"'+relative+'"%',relative,relative));
  if(asset){
   permitted=asset.uploader_id===user.id;
   if(!permitted)permitted=Boolean(await db.prepare(`SELECT 1 FROM artwork_sessions s JOIN rooms r ON r.id=s.room_id JOIN room_members m ON m.room_id=r.id
    WHERE s.asset_id=? AND m.user_id=? AND m.status='active' AND (m.role IN ('owner','member') OR s.status='active' OR s.visitor_visible=1) LIMIT 1`).get(asset.id,user.id));
   if(!permitted)permitted=await publiclyPosted();
  }else{
   const upload=await db.query('SELECT owner_id FROM community.v2_uploads WHERE filename=$1',[filename]);
   permitted=String(upload.rows[0]?.owner_id)===String(user.id);
   if(!permitted)permitted=await publiclyPosted();
  }
  if(!permitted)permitted=Boolean(await db.prepare("SELECT 1 FROM rooms r WHERE r.cover_url=? AND (r.visibility='public' OR EXISTS(SELECT 1 FROM room_members m WHERE m.room_id=r.id AND m.user_id=? AND m.status='active')) UNION ALL SELECT 1 FROM users WHERE avatar=? AND status=0 LIMIT 1").get(relative,user.id,relative));
  const target=path.join(uploadDir,filename);
  if(!permitted||!fs.existsSync(target))return json(res,404,'文件不存在或当前账号无权访问');
  const mime={'.jpg':'image/jpeg','.png':'image/png','.gif':'image/gif','.webp':'image/webp','.mp3':'audio/mpeg','.wav':'audio/wav','.ogg':'audio/ogg','.m4a':'audio/mp4','.mp4':'video/mp4','.webm':'video/webm','.mov':'video/quicktime'}[path.extname(filename)]||'application/octet-stream';
  return serveAuthorizedFile(req,res,{root:uploadDir,filename,mimeType:mime,authorize:async()=>permitted});
 }
 async function handle(req,res){
  const originalUrl=req.url,url=new URL(req.url,'http://127.0.0.1');
  if(!url.pathname.startsWith(PREFIX+'/'))return false;
  const localPath=url.pathname.slice(PREFIX.length);
  let acquiredAI=false;
  try{
   if(req.headers.origin&&req.headers.origin!==allowedOrigin)return json(res,403,'来源不允许'),true;
   if(/^\/api\/(?:admin\/)?ai(?:\/|$)/.test(localPath))return json(res,404,'接口不存在'),true;
   const mediaRead=['GET','HEAD'].includes(req.method)&&(localPath.startsWith('/uploads/')||localPath.startsWith('/legacy-media/')||localPath.startsWith('/gallery-media/')||/\/file(?:\/|$)/.test(localPath));
   let token=(req.headers.authorization||'').replace(/^Bearer /,'');
   if(!token&&mediaRead){prune(grants);const key=String(req.headers.cookie||'').split(';').map(v=>v.trim()).find(v=>v.startsWith('artlive_community_media='))?.split('=')[1];token=grants.get(key)?.token||'';}
   if(!token)return json(res,401,'请先登录'),true;
   const actor=await actorFor(token,true);
   const aiCommand=req.method==='POST'&&localPath.endsWith('/ai-critiques');
   if(aiCommand){if(activeAI>=2)return json(res,429,'当前正在处理其他 AI 请求，请稍后再试'),true;activeAI++;acquiredAI=true;}
   req.communityUser=await db.unit(()=>syncIdentity(actor));req.headers.authorization='Bearer '+token;
   const cookieGrant=String(req.headers.cookie||'').split(';').map(value=>value.trim()).find(value=>value.startsWith('artlive_community_media='))?.split('=')[1];
   if(!mediaRead&&(grants.get(cookieGrant)?.token!==token||grants.get(cookieGrant)?.expires<Date.now()))mediaGrant(res,token);
   if(localPath==='/api/media-session'&&req.method==='POST'){
    mediaGrant(res,token);
    return json(res,200,'作品访问凭证已更新'),true;
   }
   if(localPath==='/api/media-session'&&req.method==='DELETE'){
    for(const [key,value]of grants)if(value.token===token)grants.delete(key);
    return json(res,200,'作品访问凭证已撤销'),true;
   }
   if(localPath==='/api/user/logout'&&req.method==='POST'){
    for(const [key,value]of grants)if(value.token===token)grants.delete(key);
    credentials.delete(token);res.setHeader('set-cookie','artlive_community_media=; HttpOnly; SameSite=Strict; Path=/api/community/v2; Max-Age=0');return json(res,200,'已退出登录'),true;
   }
   if(localPath==='/api/ws-ticket'&&req.method==='POST'){
    prune(tickets);const ticket=opaque();tickets.set(ticket,{token,expires:Date.now()+30000});return json(res,200,'连接凭证已签发',{ticket}),true;
   }
   if(localPath.startsWith('/uploads/')&&mediaRead){await serveUpload(req,res,path.basename(localPath),req.communityUser);return true;}
   if(localPath.startsWith('/legacy-media/')&&mediaRead){req.url='/api/v1/community/media/'+localPath.split('/')[2]+url.search;return false;}
   const referenceMatch=/^\/api\/gallery-references\/([0-9a-f-]{36})$/.exec(localPath);
   if(referenceMatch&&req.method==='GET')return json(res,200,'馆藏来源',await core.painting(referenceMatch[1],token)),true;
   const legacyMatch=/^\/api\/legacy-posts\/([0-9a-f-]{36})$/.exec(localPath);
   if(legacyMatch&&req.method==='GET'){
    const row=await db.prepare('SELECT p.id FROM posts p JOIN users u ON u.id=p.user_id WHERE p.legacy_id=? AND p.status=0 AND u.status=0').get(legacyMatch[1]);
    return json(res,row?200:404,row?'原帖已适配':'帖子不存在或不可访问',row||null),true;
   }
   if(localPath.startsWith('/gallery-media/')&&mediaRead){
    const id=localPath.split('/')[2];if(!/^[0-9a-f-]{36}$/.test(id))return json(res,404,'画作不存在'),true;
    const upstream=await core.fetcher(core.origin+'/api/v1/assets/'+id+'/content',{method:req.method,headers:{authorization:'Bearer '+token,...(req.headers.range?{range:req.headers.range}:{})},redirect:'error',signal:AbortSignal.timeout(20000)});
    const headers={'cache-control':'private, no-store','x-content-type-options':'nosniff'};for(const name of ['content-type','content-length','content-range','accept-ranges']){const value=upstream.headers.get(name);if(value)headers[name]=value;}
    res.writeHead(upstream.status,headers);if(req.method==='HEAD'||!upstream.body)res.end();else Readable.fromWeb(upstream.body).pipe(res);return true;
   }
   const outcome=/^\/api\/operations\/([0-9a-f-]+)$/.exec(localPath);
   if(outcome&&req.method==='GET'){
    const row=(await db.query('SELECT state,status,result FROM community.v2_operations WHERE user_id=$1 AND request_id=$2',[actor.id,outcome[1]])).rows[0];
    return json(res,200,'操作状态',row?{state:row.state,status:row.status,result:row.result}:{state:'UNKNOWN'}),true;
   }
   let input=req, bodyBytes=null, requestId=req.headers['x-request-id'],galleryReference=null;
   const mutation=!['GET','HEAD'].includes(req.method);
   const upload=localPath==='/api/uploads'||localPath==='/api/v2/artwork-assets';
   if(mutation&&!uuid(requestId))return json(res,400,'操作标识无效'),true;
   if(mutation&&!upload){
    let size=0;const chunks=[];for await(const chunk of req){size+=chunk.length;if(size>2*1024*1024)return json(res,413,'请求内容过大'),true;chunks.push(chunk);}bodyBytes=Buffer.concat(chunks);
    let commandBytes=bodyBytes;
    if(bodyBytes.length){
     let value;try{value=JSON.parse(bodyBytes);}catch{return json(res,400,'请求格式必须为 JSON'),true;}
     if(!value||typeof value!=='object'||Array.isArray(value))return json(res,400,'请提交 JSON 对象'),true;
     for(const name of ['avatar','coverUrl'])if(name in value&&value[name]!==null&&typeof value[name]!=='string')return json(res,400,'头像和封面地址必须为字符串或 null'),true;
     const localize=item=>{if(typeof item!=='string')return item;let relative=item;try{const parsed=new URL(item,allowedOrigin);if(parsed.origin===allowedOrigin&&parsed.pathname.startsWith('/api/community/v2/'))relative=parsed.pathname+parsed.search;}catch{}return relative.replace(/^\/api\/community\/v2\/uploads\//,'/uploads/');};
     for(const name of ['audio','video','avatar','coverUrl'])if(name in value)value[name]=localize(value[name]);
     if(Array.isArray(value.images))value.images=value.images.map(localize);
     for(const name of ['avatar','coverUrl'])if(typeof value[name]==='string'&&value[name].startsWith('/uploads/')){
      const owned=(await db.query(`SELECT 1 FROM community.v2_uploads WHERE filename=$1 AND owner_id=$2 AND mime_type LIKE 'image/%' UNION ALL SELECT 1 FROM community.v2_artwork_assets WHERE uploader_id=$2 AND $3 IN(original_url,url,preview_url,thumbnail_url)`,[path.basename(value[name]),actor.id,value[name]])).rows.length;
      if(!owned)return json(res,403,'只能将自己上传的图片设为头像或封面'),true;
     }
     if(/^\/api\/posts(?:\/\d+)?$/.test(localPath)&&['POST','PUT'].includes(req.method)){
      if(value.galleryArtworkId)galleryReference=await core.painting(value.galleryArtworkId,token);
      const urls=[...(Array.isArray(value.images)?value.images:[]),value.audio,value.video].filter(Boolean);
      for(const item of urls){
       if(typeof item!=='string'||item.length>2048)return json(res,400,'媒体链接无效'),true;
       if(item.startsWith('/uploads/')){
        const owned=(await db.query('SELECT 1 FROM community.v2_uploads WHERE filename=$1 AND owner_id=$2 UNION ALL SELECT 1 FROM community.v2_artwork_assets WHERE uploader_id=$2 AND $3 IN(original_url,url,preview_url,thumbnail_url)',[path.basename(item),actor.id,item])).rows.length;
        if(!owned)return json(res,403,'只能发布自己上传的媒体'),true;
       }else if(item.startsWith('/api/community/v2/legacy-media/')){
        const id=item.split('/').at(-1);if(!/^[0-9a-f-]{36}$/.test(id)||!(await db.query('SELECT 1 FROM community.media_assets WHERE id=$1 AND owner_id=$2',[id,actor.id])).rows.length)return json(res,403,'只能发布自己上传的媒体'),true;
       }else if(item.startsWith('/api/community/v2/gallery-media/')){
        if(item!=='/api/community/v2/gallery-media/'+galleryReference?.image?.assetId)return json(res,403,'请重新核对馆藏来源'),true;
       }else{try{const parsed=new URL(item);if(!['https:','http:'].includes(parsed.protocol)||parsed.username||parsed.password)throw new Error();}catch{return json(res,400,'媒体链接需使用 HTTP 或 HTTPS'),true;}}
      }
     }
     commandBytes=Buffer.from(JSON.stringify(value));
    }
    input=Readable.from(commandBytes.length?[commandBytes]:[]);Object.assign(input,{headers:req.headers,method:req.method,communityUser:req.communityUser});
   }
   input.url=localPath+url.search;
   const fingerprint=createHash('sha256').update(req.method+' '+input.url+'\n'+(bodyBytes?.toString('utf8')||'upload:'+req.headers['content-type']+':'+req.headers['content-length'])).digest('hex');
   if(mutation){
    const inserted=await db.query("INSERT INTO community.v2_operations(user_id,request_id,fingerprint,state) VALUES($1,$2,$3,'RUNNING') ON CONFLICT DO NOTHING RETURNING request_id",[actor.id,requestId,fingerprint]);
    if(!inserted.rowCount){const prior=(await db.query('SELECT * FROM community.v2_operations WHERE user_id=$1 AND request_id=$2',[actor.id,requestId])).rows[0];
     if(prior.fingerprint!==fingerprint||upload)return json(res,409,'操作标识已使用，请读取操作结果'),true;
     if(prior.state==='RUNNING')return json(res,409,'请求仍在处理中，请读取操作结果'),true;
     res.writeHead(prior.status,{'content-type':'application/json','cache-control':'private, no-store'});res.end(JSON.stringify(prior.result));return true;
    }
   }
   const fileResponse=mediaRead&&localPath.includes('/file');
   const record=fileResponse?res:capture();
   try{
    await db.unit(async()=>{
     const current=await db.prepare('SELECT * FROM users WHERE id=? AND status=0').get(actor.id);if(!current)throw new Failure(403,'COMMUNITY_ACCOUNT_DISABLED','账号已被禁用');input.communityUser=current;
     if(upload){await db.query("SELECT pg_advisory_xact_lock(hashtextextended($1,0))",['community-upload:'+actor.id]);const bytes=Number((await db.query('SELECT COALESCE((SELECT sum(bytes) FROM community.v2_uploads WHERE owner_id=$1),0)+COALESCE((SELECT sum(size) FROM community.v2_artwork_assets WHERE uploader_id=$1),0) AS bytes',[actor.id])).rows[0].bytes);if(bytes+Number(req.headers['content-length']||0)>512*1024*1024)throw Object.assign(new Error('当前账号社区媒体空间不足'),{status:413});}
     await reference.handle(input,record);
     if(!fileResponse&&!record.finished)throw new Error('Reference handler did not return a response');
     if(record.statusCode>=400)throw Object.assign(new Error('Reference rejected command'),{response:record});
     if(upload&&localPath==='/api/uploads'){
      const packet=JSON.parse(Buffer.concat(record.chunks).toString()),value=packet.data;
      let legacyId=null;
      if(value?.type==='image'){
       const filename=path.basename(value.url),target=path.join(uploadDir,filename),metadata=await sharp(target,{animated:true}).metadata();
       if(!metadata.width||!metadata.height||!['jpeg','png','gif','webp'].includes(metadata.format))throw Object.assign(new Error('图片格式无效'),{status:415});
       if(metadata.width*(metadata.pageHeight||metadata.height)<=36000000){
        legacyId=randomUUID();const originalName=metadata.format==='gif'?legacyId+'.webp':filename,previewName=legacyId+'.preview.webp';
        if(metadata.format==='gif')await sharp(target).rotate().webp({quality:90}).toFile(path.join(uploadDir,originalName));
        await sharp(target).rotate().resize({width:1600,height:1600,fit:'inside',withoutEnlargement:true}).webp({quality:86}).toFile(path.join(uploadDir,previewName));
        const original=fs.readFileSync(path.join(uploadDir,originalName)),oriented=[5,6,7,8].includes(metadata.orientation);
        if(original.length<=10485760)await db.query('INSERT INTO community.media_assets(id,owner_id,filename,original_key,preview_key,mime_type,bytes,width,height,sha256) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)',[legacyId,actor.id,filename,'reference/'+originalName,'reference/'+previewName,metadata.format==='gif'?'image/webp':value.mimeType,original.length,oriented?(metadata.pageHeight||metadata.height):metadata.width,oriented?metadata.width:(metadata.pageHeight||metadata.height),createHash('sha256').update(original).digest('hex')]);
        else legacyId=null;
       }
      }
      await db.query('INSERT INTO community.v2_uploads(filename,owner_id,mime_type,bytes,legacy_id) VALUES($1,$2,$3,$4,$5)',[path.basename(value.url),actor.id,value.mimeType,value.size,legacyId]);
     }
     if(/^\/api\/posts(?:\/\d+)?$/.test(localPath)&&['POST','PUT'].includes(req.method)){
      const packet=JSON.parse(Buffer.concat(record.chunks).toString()),value=packet.data;
      const post=await db.prepare('SELECT * FROM posts WHERE id=?').get(value.id),urls=JSON.parse(post.images),ids=[];
      for(const image of urls){let id;if(image.startsWith('/uploads/'))id=(await db.query('SELECT legacy_id FROM community.v2_uploads WHERE filename=$1 AND owner_id=$2',[path.basename(image),actor.id])).rows[0]?.legacy_id;else if(image.startsWith('/api/community/v2/legacy-media/'))id=image.split('/').at(-1);if(id)ids.push(id);}
      await db.query('DELETE FROM community.post_images WHERE post_id=$1 AND revision_id IS NULL',[post.legacy_id]);
      for(let i=0;i<ids.length;i++)await db.query('INSERT INTO community.post_images(post_id,asset_id,position) VALUES($1,$2,$3)',[post.legacy_id,ids[i],i]);
      await db.query("UPDATE community.posts SET title=CASE WHEN title='' THEN $2 ELSE title END,kind=CASE WHEN $3>0 THEN 'artwork' ELSE kind END,gallery_artwork_id=COALESCE($4,gallery_artwork_id) WHERE id=$1",[post.legacy_id,String(post.content).split('\n')[0].slice(0,100)||galleryReference?.title||'未命名作品',ids.length,galleryReference?.paintingId||null]);
      value.legacyId=post.legacy_id;value.hasGrowth=ids.length>0&&ids.length===urls.length;record.chunks=[Buffer.from(JSON.stringify(packet))];
     }
     if(mutation)await db.query("UPDATE community.v2_operations SET state='COMMITTED',status=$3,result=$4 WHERE user_id=$1 AND request_id=$2",[actor.id,requestId,record.statusCode,JSON.parse(Buffer.concat(record.chunks).toString())]);
    },!aiCommand&&!upload);
    if(!fileResponse)flush(res,record);
   }catch(error){
    const failure=error.response||failureRecord(error);
    if(mutation){
     const updated=await db.query("UPDATE community.v2_operations SET state='FAILED',status=$3,result=$4 WHERE user_id=$1 AND request_id=$2 AND state='RUNNING' RETURNING request_id",[actor.id,requestId,failure.statusCode,JSON.parse(Buffer.concat(failure.chunks).toString())]);
     if(!updated.rowCount){
      const prior=(await db.query('SELECT state,status,result FROM community.v2_operations WHERE user_id=$1 AND request_id=$2',[actor.id,requestId])).rows[0];
      if(prior?.state==='COMMITTED'){res.writeHead(prior.status,{'content-type':'application/json','cache-control':'private, no-store'});res.end(JSON.stringify(prior.result));return true;}
     }
    }
    if(!res.headersSent)flush(res,failure);
   }
   return true;
  }catch(error){if(!res.headersSent)json(res,error.status||503,error.status&&error.status<500?error.message:'社区暂时不可用，请稍后重新读取。');return true;}
  finally{if(acquiredAI)activeAI--;if(req.url===localPath+url.search)req.url=originalUrl;}
 }
 let credentialTimer;
 return {handle,async authorizeLegacy(actor,token){return db.unit(async()=>{const identity=await actorFor(token,true);if(String(identity.id)!==actor.id)throw new Failure(403,'IDENTITY_MISMATCH','平台身份不一致');return syncIdentity(identity);});},attach(server){
  wss=reference.roomFeature.attachWebSocket(server);
  wss.on('connection',socket=>{
   const send=socket.send.bind(socket),close=socket.close.bind(socket);
   socket.send=(...args)=>db.afterCommit(()=>{if(socket.readyState===1)send(...args);});
   socket.close=(...args)=>db.afterCommit(()=>close(...args));
  });
  let checking=false;
  credentialTimer=setInterval(async()=>{
   if(checking)return;checking=true;
   try{await Promise.all([...wss.clients].map(async socket=>{if(!socket.authToken)return;try{await actorFor(socket.authToken,true);const user=await db.prepare('SELECT status FROM users WHERE id=?').get(socket.userId);if(!user||user.status!==0)socket.close(4403,'账号已被禁用');}catch{socket.close(4401,'平台身份已失效');}}));}finally{checking=false;}
  },15000);credentialTimer.unref();
 },async close(){
  clearInterval(credentialTimer);
  for(const socket of wss?.clients||[])socket.terminate();
  if(wss)await new Promise(resolve=>wss.close(resolve));
  while(pending.size)await Promise.all([...pending]);
  await db.unit(()=>reference.roomFeature.shutdown());
  grants.clear();tickets.clear();credentials.clear();
 }};
}
