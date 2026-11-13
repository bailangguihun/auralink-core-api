import { createHash, randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import sharp from 'sharp';
import { Failure, invalid } from './errors.mjs';
import { imageView } from './learning-contract.mjs';
import { serveAuthorizedFile } from './media.mjs';

export const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const formats = { 'image/jpeg': 'jpeg', 'image/png': 'png', 'image/webp': 'webp' };
sharp.cache({ memory: 32, files: 0, items: 16 });
sharp.concurrency(2);
export class CommunityMedia {
  decoding = 0;
  constructor(root, store) {
    if (!isAbsolute(root)) throw new Error('COMMUNITY_MEDIA_ROOT must be an absolute managed directory');
    this.root = root;
    this.store = store;
  }
  async upload(req, actor, requestId) {
    const mime = String(req.headers['content-type'] || '').split(';')[0].toLowerCase();
    if (!formats[mime]) throw new Failure(415, 'IMAGE_FORMAT_UNSUPPORTED', '请选择 JPG、PNG 或 WebP 图片。');
    if (Number(req.headers['content-length']) > MAX_IMAGE_BYTES) throw new Failure(413, 'IMAGE_TOO_LARGE', '每张图片最多 10 MB。');
    let size = 0;
    const chunks = [];
    for await (const chunk of req) {
      size += chunk.length;
      if (size > MAX_IMAGE_BYTES) throw new Failure(413, 'IMAGE_TOO_LARGE', '每张图片最多 10 MB。');
      chunks.push(chunk);
    }
    const bytes = Buffer.concat(chunks);
    if (!bytes.length) throw invalid('图片为空。');
    if (this.decoding >= 2) throw new Failure(429, 'IMAGE_DECODER_BUSY', '当前正在处理其他图片，请稍后再选择上传。');
    let metadata, preview;
    this.decoding++;
    try {
      const image = sharp(bytes, { limitInputPixels: 36_000_000, failOn: 'warning' });
      metadata = await image.metadata();
      if (metadata.format !== formats[mime] || !metadata.width || !metadata.height || (metadata.pages || 1) !== 1) throw new Error('Invalid image');
      preview = await image.rotate().resize({ width: 1600, height: 1600, fit: 'inside', withoutEnlargement: true }).webp({ quality: 86 }).toBuffer();
    } catch {
      throw invalid('图片内容无法读取，请选择有效的静态图片（最多 3600 万像素）。');
    } finally { this.decoding--; }
    let filename;
    try { filename = decodeURIComponent(String(req.headers['x-file-name'] || '作品图片')); }
    catch { throw invalid('图片名称无效。'); }
    filename = filename.replace(/[\x00-\x1f\x7f/\\]/g, '').slice(0, 180) || '作品图片';
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    const result = await this.store.mutate(actor, requestId, ['upload', sha256, mime, filename], async client => {
      const total = Number((await client.query('SELECT COALESCE(sum(bytes),0) AS n FROM community.media_assets WHERE owner_id=$1', [actor.id])).rows[0].n);
      if (total + size > 512 * 1024 * 1024) throw new Failure(413, 'MEDIA_QUOTA_EXCEEDED', '当前账号社区图片空间不足。');
      const id = randomUUID(), originalKey = `${id}.${metadata.format}`, previewKey = `${id}.preview.webp`;
      await mkdir(this.root, { mode: 0o700, recursive: true });
      // Files are immutable. A failed/ambiguous database commit never causes a
      // potentially published file to be deleted. Unreferenced files stay private.
      await writeFile(join(this.root, originalKey), bytes, { flag: 'wx', mode: 0o600 });
      await writeFile(join(this.root, previewKey), preview, { flag: 'wx', mode: 0o600 });
      const rotated = [5, 6, 7, 8].includes(metadata.orientation);
      await client.query('INSERT INTO community.media_assets(id,owner_id,filename,original_key,preview_key,mime_type,bytes,width,height,sha256) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)',
        [id, actor.id, filename, originalKey, previewKey, mime, size, rotated ? metadata.height : metadata.width, rotated ? metadata.width : metadata.height, sha256]);
      return { kind: 'upload', assetId: id };
    });
    return { ...result, image: imageView(await this.store.mediaFor(actor, result.assetId)) };
  }
  async serve(req, res, actor, id, variant) {
    const row = await this.store.mediaFor(actor, id);
    return serveAuthorizedFile(req, res, { root: this.root,
      filename: variant === 'preview' ? row.preview_key : row.original_key,
      mimeType: variant === 'preview' ? 'image/webp' : row.mime_type,
      authorize: async () => true });
  }
}
