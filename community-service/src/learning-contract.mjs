import { content, invalid, strictObject, uuid } from './errors.mjs';

export const DIMENSIONS = ['构图', '笔墨', '设色', '造型', '意境', '题款', '综合'];
export const POST_FIELDS = ['requestId', 'content', 'galleryArtworkId', 'title', 'kind', 'creationStage',
  'creationGoal', 'helpTopics', 'tags', 'imageIds'];
export function text(value, max, fallback = '') {
  return value === undefined ? fallback : value === '' ? '' : content(value, max);
}
export function strings(value, maxItems, maxLength, allowed) {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.length > maxItems) throw invalid();
  const result = [...new Set(value.map(item => content(item, maxLength)))];
  if (allowed && result.some(item => !allowed.includes(item))) throw invalid();
  return result;
}
export function postCommand(value, learning) {
  strictObject(value, learning ? POST_FIELDS : ['requestId', 'content', 'galleryArtworkId']);
  const base = { content: content(value.content, 5000), galleryArtworkId: value.galleryArtworkId == null ? null : uuid(value.galleryArtworkId) };
  if (!learning || !POST_FIELDS.slice(3).some(key => key in value)) return base;
  const kind = value.kind ?? 'discussion';
  if (!['discussion', 'artwork'].includes(kind)) throw invalid();
  const imageIds = strings(value.imageIds, 9, 36).map(uuid);
  const title = text(value.title, 100), stage = text(value.creationStage, 16);
  if (stage && !['创作', '临摹', '写生', '修改'].includes(stage)) throw invalid();
  if (kind === 'artwork' && (!title || !imageIds.length)) throw invalid('作品求助需要作品名称和至少一张自己的作品图片。');
  return { ...base, title, kind, creationStage: stage, creationGoal: text(value.creationGoal, 1000),
    helpTopics: strings(value.helpTopics, 6, 16, DIMENSIONS),
    tags: [...new Set(strings(value.tags, 5, 20).map(tag => tag.replace(/^#+/, '')).filter(Boolean))], imageIds };
}
export function commentCommand(value, learning) {
  strictObject(value, learning ? ['requestId', 'content', 'parentId', 'dimension', 'suggestion', 'revisionId'] : ['requestId', 'content']);
  const result = { content: content(value.content, 1000) };
  if (!learning || !['parentId', 'dimension', 'suggestion', 'revisionId'].some(key => key in value)) return result;
  const dimension = text(value.dimension, 16);
  if (dimension && !DIMENSIONS.includes(dimension)) throw invalid();
  return { ...result, parentId: value.parentId ? uuid(value.parentId) : null, dimension,
    suggestion: text(value.suggestion, 1000), revisionId: value.revisionId ? uuid(value.revisionId) : null };
}
export function learningQuery(url) {
  const result = {};
  for (const name of ['q', 'tag', 'kind', 'workflow', 'sort']) result[name] = url.searchParams.get(name) || '';
  if ([...result.q].length > 100 || [...result.tag].length > 20 ||
      !['', 'discussion', 'artwork'].includes(result.kind) ||
      !['', 'seeking_feedback', 'revising', 'completed'].includes(result.workflow) ||
      !['', 'latest', 'popular'].includes(result.sort)) throw invalid();
  return result;
}
export function imageView(row) {
  return { id: row.id, filename: row.filename, mimeType: row.mime_type, bytes: row.bytes,
    width: row.width, height: row.height, sha256: row.sha256,
    previewUrl: `/api/community/media/${row.id}?variant=preview`, contentUrl: `/api/community/media/${row.id}` };
}
