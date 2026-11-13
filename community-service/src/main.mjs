import { CoreClient } from './core.mjs';
import { postgresDatabase } from './database.mjs';
import { CommunityStore } from './learning-store.mjs';
import { createCommunityServer } from './server.mjs';
import { CommunityMedia } from './learning-media.mjs';
import {createReferenceGateway} from './v2/gateway.mjs';

const origin = new URL(process.env.COMMUNITY_WEB_ORIGIN || '');
if (!['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.href !== origin.origin + '/') {
  throw new Error('COMMUNITY_WEB_ORIGIN must be an explicit web origin');
}
const port = Number(process.env.COMMUNITY_PORT);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('COMMUNITY_PORT is required');
const core = new CoreClient(process.env.COMMUNITY_CORE_ORIGIN);
const database = postgresDatabase(process.env.COMMUNITY_DATABASE_URL);
await database.verifyRuntimeRole();
const store = new CommunityStore(database);
if (!await store.ready()) throw new Error('Explicit community migration is required');
const media = store.learning && process.env.COMMUNITY_MEDIA_ROOT ? new CommunityMedia(process.env.COMMUNITY_MEDIA_ROOT, store) : null;
const reference=store.reference&&process.env.COMMUNITY_MEDIA_ROOT?await createReferenceGateway({database,core,mediaRoot:process.env.COMMUNITY_MEDIA_ROOT,allowedOrigin:origin.origin}):null;
const server = createCommunityServer({ store, core, media, reference, allowedOrigin: origin.origin });
server.listen(port, '127.0.0.1', () => console.log(JSON.stringify({ event: 'community_ready', port })));
let closing = false;
async function close() {
  if (closing) return;
  closing = true;
  console.log(JSON.stringify({ event: 'community_draining' }));
  // No forced exit, retry or transaction replay. The lifecycle owner reports
  // STOPPING if its observation deadline elapses; it must not claim STOPPED.
  await server.drain();
  await database.close();
  console.log(JSON.stringify({ event: 'community_stopped' }));
}
process.on('SIGTERM', close);
process.on('SIGINT', close);
