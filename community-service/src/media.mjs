import { open, realpath } from 'node:fs/promises';
import { constants } from 'node:fs';
import { resolve, sep } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Failure } from './errors.mjs';

/** Reserved shared media primitive. No HTTP upload/media route is enabled in phase one. */
export async function serveAuthorizedFile(req, res, { root, filename, mimeType, authorize }) {
  if (!['GET', 'HEAD'].includes(req.method)) throw new Failure(405, 'METHOD_NOT_ALLOWED', '请求方法不支持。');
  if (!await authorize()) throw new Failure(404, 'ASSET_NOT_FOUND', '资源不存在或不可访问。');
  const directory = await realpath(root);
  const target = await realpath(resolve(directory, filename));
  if (!target.startsWith(directory + sep)) throw new Failure(404, 'ASSET_NOT_FOUND', '资源不存在或不可访问。');
  const file = await open(target, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await file.stat();
    if (!stat.isFile()) throw new Failure(404, 'ASSET_NOT_FOUND', '资源不存在或不可访问。');
    const headers = { 'content-type': mimeType, 'cache-control': 'private, no-store',
      'x-content-type-options': 'nosniff', 'accept-ranges': 'bytes' };
    let start = 0, end = stat.size - 1, status = 200;
    // HEAD ignores Range. For GET, single, suffix and open-ended byte ranges are supported.
    if (req.method === 'GET' && req.headers.range) {
      const match = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range);
      if (!match || (!match[1] && !match[2]) || !stat.size) {
        res.writeHead(416, { ...headers, 'content-range': 'bytes */' + stat.size });
        return res.end();
      }
      if (!match[1]) start = Math.max(0, stat.size - Number(match[2]));
      else start = Number(match[1]);
      if (match[1] && match[2]) end = Math.min(end, Number(match[2]));
      if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start >= stat.size || start > end || (!match[1] && Number(match[2]) === 0)) {
        res.writeHead(416, { ...headers, 'content-range': 'bytes */' + stat.size });
        return res.end();
      }
      status = 206;
      headers['content-range'] = 'bytes ' + start + '-' + end + '/' + stat.size;
    }
    headers['content-length'] = String(Math.max(0, end - start + 1));
    res.writeHead(status, headers);
    if (req.method === 'HEAD' || !stat.size) return res.end();
    // Exactly one response and one stream; no fallthrough to a second 200 response.
    await pipeline(file.createReadStream({ start, end, autoClose: false }), res);
  } finally { await file.close(); }
}
