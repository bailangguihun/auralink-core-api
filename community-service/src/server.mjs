import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { Failure, content, invalid, strictObject, uuid } from './errors.mjs';

const PREFIX = '/api/v1/community';
function reply(res, status, value) {
  if (res.destroyed) return;
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8',
    'cache-control': 'private, no-store', 'x-content-type-options': 'nosniff' });
  res.end(JSON.stringify(value));
}
async function body(req) {
  if (!/^application\/json(?:;|$)/i.test(req.headers['content-type'] || '')) throw new Failure(415, 'JSON_REQUIRED', '请提交 JSON。');
  let length = 0;
  const chunks = [];
  for await (const chunk of req) {
    length += chunk.length;
    if (length > 32768) throw new Failure(413, 'REQUEST_TOO_LARGE', '请求内容过大。');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw invalid(); }
}
function pageQuery(url, allowed = ['page', 'size']) {
  if ([...url.searchParams.keys()].some(key => !allowed.includes(key) || url.searchParams.getAll(key).length !== 1)) throw invalid();
  const page = Number(url.searchParams.get('page') ?? '0');
  const size = Number(url.searchParams.get('size') ?? '12');
  if (!Number.isSafeInteger(page) || page < 0 || page > 10000 || !Number.isInteger(size) || size < 1 || size > 24) throw invalid();
  return { page, size };
}
export function createCommunityServer({ store, core, allowedOrigin, logger = console }) {
  return http.createServer({ requestTimeout: 15000, headersTimeout: 10000, maxHeaderSize: 16384 }, async (req, res) => {
    const correlationId = randomUUID();
    try {
      const url = new URL(req.url, 'http://127.0.0.1');
      if (url.pathname === '/health' && req.method === 'GET') {
        if (!await store.ready()) throw new Error('schema mismatch');
        return reply(res, 200, { status: 'UP', contractVersion: 1 });
      }
      if (!url.pathname.startsWith(PREFIX + '/')) throw new Failure(404, 'NOT_FOUND', '接口不存在。');
      if (req.headers.origin && req.headers.origin !== allowedOrigin) throw new Failure(403, 'ORIGIN_REJECTED', '来源不允许。');
      const auth = req.headers.authorization;
      if (typeof auth !== 'string' || !/^Bearer [^\s,]{1,8192}$/.test(auth)) throw new Failure(401, 'UNAUTHORIZED', '请先登录。');
      const token = auth.slice(7);
      const actor = await core.authenticate(token);
      const path = url.pathname.slice(PREFIX.length);
      if (req.method === 'GET' && path === '/capabilities') return reply(res, 200, {
        contractVersion: 1, actor, posts: true, galleryReferences: true,
        uploads: false, rooms: false, voice: false, coins: false, ai: false,
      });
      const operationMatch = path.match(/^\/operations\/([^/]+)$/);
      if (req.method === 'GET' && operationMatch) return reply(res, 200, await store.outcome(actor, uuid(operationMatch[1])));
      const sourceMatch = path.match(/^\/gallery-references\/([^/]+)$/);
      if (req.method === 'GET' && sourceMatch) return reply(res, 200, await core.painting(uuid(sourceMatch[1]), token));
      const postMatch = path.match(/^\/posts\/([^/]+)(?:\/(comments|like|favorite))?$/);
      if (postMatch) uuid(postMatch[1]);
      if (req.method === 'GET' && (path === '/posts' || (postMatch && !postMatch[2]))) {
        const { page, size } = pageQuery(url, ['page', 'size', 'view']);
        const view = url.searchParams.get('view') || 'all';
        if (!['all', 'mine', 'favorites'].includes(view)) throw invalid();
        const result = await store.posts(actor, page, size, view, postMatch?.[1] || null);
        if (postMatch && !result.rows.length) throw new Failure(404, 'POST_NOT_FOUND', '帖子不存在或已删除。');
        // Deduplicate gallery checks within a response; no cross-user metadata cache.
        const references = new Map();
        for (const row of result.rows) if (row.gallery_artwork_id && !references.has(row.gallery_artwork_id)) {
          references.set(row.gallery_artwork_id, core.reference(row.gallery_artwork_id, token));
        }
        const items = await Promise.all(result.rows.map(async row => ({
          id: row.id, content: row.content,
          author: { id: String(row.author_id), displayName: row.display_name },
          createdAt: new Date(row.created_at).toISOString(), commentCount: row.comment_count, likeCount: row.like_count,
          liked: row.liked, favorited: row.favorited, galleryReference: row.gallery_artwork_id ? await references.get(row.gallery_artwork_id) : null,
        })));
        return reply(res, 200, postMatch ? items[0] : { items, page, hasNext: result.hasNext });
      }
      if (req.method === 'GET' && postMatch?.[2] === 'comments') {
        const { page, size } = pageQuery(url);
        return reply(res, 200, await store.comments(postMatch[1], page, size));
      }
      if (req.method === 'POST' && path === '/posts') {
        const value = strictObject(await body(req), ['requestId', 'content', 'galleryArtworkId']);
        const command = { content: content(value.content, 5000), galleryArtworkId: value.galleryArtworkId == null ? null : uuid(value.galleryArtworkId) };
        const result = await store.createPost(actor, uuid(value.requestId), command, id => core.painting(id, token));
        return reply(res, 200, { state: 'COMMITTED', result });
      }
      if (req.method === 'POST' && postMatch?.[2] === 'comments') {
        const value = strictObject(await body(req), ['requestId', 'content']);
        return reply(res, 200, { state: 'COMMITTED', result: await store.comment(actor, uuid(value.requestId), postMatch[1], content(value.content, 1000)) });
      }
      if (req.method === 'PUT' && ['like', 'favorite'].includes(postMatch?.[2])) {
        const value = strictObject(await body(req), ['requestId', 'enabled']);
        if (typeof value.enabled !== 'boolean') throw invalid();
        return reply(res, 200, { state: 'COMMITTED', result: await store.reaction(actor, uuid(value.requestId), postMatch[1], postMatch[2], value.enabled) });
      }
      if (req.method === 'DELETE' && postMatch && !postMatch[2]) {
        const value = strictObject(await body(req), ['requestId']);
        return reply(res, 200, { state: 'COMMITTED', result: await store.remove(actor, uuid(value.requestId), postMatch[1]) });
      }
      throw new Failure(404, 'NOT_FOUND', '接口不存在。');
    } catch (error) {
      const known = error instanceof Failure;
      if (!known) logger.error(JSON.stringify({ event: 'community_request_failed', correlationId, code: 'INTERNAL_ERROR',
        method: req.method,
        causeType: typeof error.name === 'string' && /^[A-Za-z]{1,50}$/.test(error.name) ? error.name : 'Error',
        causeCode: typeof error.code === 'string' && /^[A-Z0-9_]{1,50}$/.test(error.code) ? error.code : null }));
      reply(res, known ? error.status : 503, {
        code: known ? error.code : 'COMMUNITY_RESULT_UNCERTAIN',
        message: known ? error.message : '请求结果尚未确认，请读取操作记录，不要重复提交。',
        correlationId,
      });
    }
  });
}
