import { Failure, uuid } from './errors.mjs';

/** Return only the gallery's logical original-image path, never a remote URL or storage path. */
function galleryImage(image) {
  const match = typeof image?.contentUrl === 'string'
    && /^\/api\/v1\/assets\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\/content$/i.exec(image.contentUrl);
  if (!match || image.assetId !== match[1]) return null;
  const positive = value => Number.isSafeInteger(value) && value > 0 ? value : null;
  return {
    assetId: image.assetId, contentUrl: image.contentUrl,
    downloadUrl: `/api/v1/assets/${image.assetId}/download`,
    mimeType: typeof image.mimeType === 'string' && /^image\/[a-z0-9.+-]+$/i.test(image.mimeType) ? image.mimeType : '',
    width: positive(image.width), height: positive(image.height), fileSize: positive(image.fileSize),
  };
}

export function loopbackOrigin(value) {
  const url = new URL(value);
  if (url.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(url.hostname) ||
      !url.port || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('CORE_ORIGIN must be an explicit loopback HTTP origin');
  }
  return url.origin;
}

/** Only Core verifies login tokens. Never decode a prototype JWT or accept actor IDs. */
export class CoreClient {
  constructor(origin, fetcher = fetch) {
    this.origin = loopbackOrigin(origin);
    this.fetcher = fetcher;
  }
  async read(path, token) {
    let response;
    try {
      response = await this.fetcher(this.origin + path, {
        headers: { authorization: 'Bearer ' + token, accept: 'application/json' },
        redirect: 'error', cache: 'no-store', signal: AbortSignal.timeout(5000),
      });
    } catch {
      throw new Failure(503, 'CORE_UNAVAILABLE', '平台服务暂时不可用，请稍后重新读取。');
    }
    if (response.status === 401) throw new Failure(401, 'UNAUTHORIZED', '登录已失效，请重新登录。');
    if (response.status === 403) throw new Failure(403, 'FORBIDDEN', '当前账号无权访问。');
    if (response.status === 404) throw new Failure(404, 'SOURCE_UNAVAILABLE', '原作品不存在或暂不可访问。');
    if (!response.ok) throw new Failure(503, 'CORE_UNAVAILABLE', '平台服务暂时不可用。');
    try {
      if (!response.headers.get('content-type')?.includes('application/json')) throw new Error();
      const reader = response.body.getReader();
      const chunks = [];
      let length = 0;
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          length += value.length;
          if (length > 512 * 1024) { await reader.cancel(); throw new Error(); }
          chunks.push(value);
        }
      } finally { reader.releaseLock(); }
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
      throw new Failure(502, 'CORE_CONTRACT_INVALID', '平台返回格式不完整。');
    }
  }
  async authenticate(token) {
    const result = await this.read('/api/user/profile', token);
    const user = result?.data;
    if (result?.success !== true || !Number.isSafeInteger(user?.id) || user.id <= 0 ||
        typeof user.username !== 'string' || !user.username ||
        (user.fullName != null && typeof user.fullName !== 'string')) {
      throw new Failure(502, 'CORE_CONTRACT_INVALID', '平台身份返回格式不完整。');
    }
    return { id: String(user.id), displayName: (user.fullName || user.username).slice(0, 160) };
  }
  async communityIdentity(token) {
    const result=await this.read('/api/user/community-identity',token), user=result?.data;
    if(result?.success!==true||!Number.isSafeInteger(user?.id)||user.id<=0||typeof user.username!=='string'||!user.username||!Array.isArray(user.authorities)||user.authorities.some(value=>typeof value!=='string')) {
      throw new Failure(502,'CORE_CONTRACT_INVALID','平台社区身份返回格式不完整。');
    }
    return {id:user.id,username:user.username,displayName:(user.fullName||user.username).slice(0,160),email:user.email||null,admin:user.authorities.includes('ROLE_ADMIN')};
  }
  async painting(id, token) {
    const value = await this.read('/api/v1/paintings/' + uuid(id), token);
    if (value?.paintingId !== id || value.status !== 'ACTIVE' ||
        value.visibleInGallery !== true || value.imageAvailable !== true) {
      throw new Failure(404, 'SOURCE_UNAVAILABLE', '原作品不存在或暂不可访问。');
    }
    // Reuse the currently accessible gallery image; Community stores only its
    // painting ID and never copies image bytes or fetches a supplied media URL.
    return {
      state: 'AVAILABLE', paintingId: id,
      title: typeof value.title === 'string' ? value.title.slice(0, 300) : '无题',
      authorName: typeof value.authorName === 'string' ? value.authorName.slice(0, 200) : '',
      period: typeof value.creationDynastyNormalized === 'string' ? value.creationDynastyNormalized.slice(0, 80) : '',
      image: galleryImage(value.image),
    };
  }
  async reference(id, token) {
    if (!id) return null;
    try { return await this.painting(id, token); }
    catch (error) {
      if (error.status === 401) throw error;
      if ([403, 404].includes(error.status)) return { state: 'UNAVAILABLE', paintingId: id };
      if ([502, 503].includes(error.status)) return { state: 'UNVERIFIED', paintingId: id };
      throw error;
    }
  }
}
