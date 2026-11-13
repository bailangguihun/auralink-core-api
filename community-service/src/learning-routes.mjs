import { Failure, content, invalid, strictObject, uuid } from './errors.mjs';
import { DIMENSIONS, strings, text, imageView } from './learning-contract.mjs';

export async function learningRoute({ req, res, path, url, actor, store, media, body, pageQuery, reply }) {
  if (!store.learning) return false;
  const parts = path.slice(1).split('/');
  const send = result => reply(res, 200, result);
  const committed = async operation => send({ state: 'COMMITTED', result: await operation });
  const json = async allowed => strictObject(await body(req), ['requestId', ...allowed]);
  const postId = parts[0] === 'posts' && parts[1] ? uuid(parts[1]) : null;
  if (path === '/uploads' && req.method === 'POST') {
    if (!media) throw new Failure(409, 'COMMUNITY_UPLOADS_DISABLED', '社区图片上传暂未配置。');
    await committed(media.upload(req, actor, uuid(req.headers['x-request-id'])));
  } else if (parts[0] === 'media' && parts.length === 2 && ['GET', 'HEAD'].includes(req.method)) {
    if (!media) throw new Failure(404, 'MEDIA_UNAVAILABLE', '图片暂不可用。');
    const id = uuid(parts[1]);
    if ([...url.searchParams.keys()].some(key => key !== 'variant') || url.searchParams.getAll('variant').length > 1 || !['', 'preview'].includes(url.searchParams.get('variant') || '')) throw invalid();
    await media.serve(req, res, actor, id, url.searchParams.get('variant'));
  } else if (parts[0] === 'media' && parts.length === 3 && parts[2] === 'metadata' && req.method === 'GET') {
    send(imageView(await store.mediaFor(actor, uuid(parts[1]))));
  } else if (path === '/tags' && req.method === 'GET') {
    send({ items: await store.tags() });
  } else if (path === '/notifications' && req.method === 'GET') {
    const { page, size } = pageQuery(url);
    send(await store.notifications(actor, page, size));
  } else if (parts[0] === 'notifications' && parts.length === 3 && parts[2] === 'read' && req.method === 'PUT') {
    const value = await json([]);
    await committed(store.readNotifications(actor, uuid(value.requestId), uuid(parts[1])));
  } else if (path === '/cases' && req.method === 'GET') {
    const { page, size } = pageQuery(url, ['page', 'size', 'q', 'dimension']);
    const query = text(url.searchParams.get('q') || '', 100), dimension = text(url.searchParams.get('dimension') || '', 16);
    if (dimension && !DIMENSIONS.includes(dimension)) throw invalid();
    send(await store.cases(page, size, query, dimension));
  } else if (parts[0] === 'cases' && parts.length === 2 && req.method === 'PUT') {
    const value = await json(['title', 'summary', 'status']);
    if (!['draft', 'published'].includes(value.status)) throw invalid();
    await committed(store.publishCase(actor, uuid(value.requestId), uuid(parts[1]), {
      title: content(value.title, 160), summary: content(value.summary, 3000), status: value.status }));
  } else if (postId && parts.length === 2 && req.method === 'PUT') {
    const value = await json(['content', 'title', 'tags', 'creationGoal']);
    await committed(store.editPost(actor, uuid(value.requestId), postId, {
      content: content(value.content, 5000), title: text(value.title, 100), tags: strings(value.tags, 5, 20), creationGoal: text(value.creationGoal, 1000) }));
  } else if (postId && parts[2] === 'growth' && parts.length === 3 && req.method === 'GET') {
    send(await store.growth(postId, actor));
  } else if (postId && parts[2] === 'workflow' && parts.length === 3 && req.method === 'PUT') {
    const value = await json(['status']);
    if (!['seeking_feedback', 'revising', 'completed'].includes(value.status)) throw invalid();
    await committed(store.workflow(actor, uuid(value.requestId), postId, value.status));
  } else if (postId && parts[2] === 'tasks' && parts.length === 3 && req.method === 'POST') {
    const value = await json([]);
    await committed(store.generateTasks(actor, uuid(value.requestId), postId));
  } else if (postId && parts[2] === 'case' && parts.length === 3 && req.method === 'POST') {
    const value = await json([]);
    await committed(store.buildCase(actor, uuid(value.requestId), postId));
  } else if (postId && parts[2] === 'revisions' && parts.length === 3 && req.method === 'POST') {
    const value = await json(['imageIds', 'note', 'reflection']);
    const imageIds = strings(value.imageIds, 9, 36).map(uuid);
    if (!imageIds.length) throw invalid('请上传本轮修改后的作品。');
    await committed(store.revision(actor, uuid(value.requestId), postId, {
      imageIds, note: content(value.note, 2000), reflection: text(value.reflection, 2000) }));
  } else if (postId && parts[2] === 'revisions' && parts[4] === 'review' && parts.length === 5 && req.method === 'POST') {
    const value = await json([]);
    await committed(store.review(actor, uuid(value.requestId), postId, uuid(parts[3])));
  } else if (parts[0] === 'comments' && parts.length === 2 && req.method === 'GET') {
    send(await store.commentDetail(uuid(parts[1])));
  } else if (parts[0] === 'comments' && parts[2] === 'adopt' && parts.length === 3 && req.method === 'PUT') {
    const value = await json(['enabled']);
    if (typeof value.enabled !== 'boolean') throw invalid();
    await committed(store.adopt(actor, uuid(value.requestId), uuid(parts[1]), value.enabled));
  } else if (parts[0] === 'tasks' && parts.length === 2 && req.method === 'PUT') {
    const value = await json(['status']);
    if (!['todo', 'done'].includes(value.status)) throw invalid();
    await committed(store.task(actor, uuid(value.requestId), uuid(parts[1]), value.status));
  } else return false;
  return true;
}
