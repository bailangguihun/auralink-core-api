import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture } from './support.mjs';
import { CommunityStore } from '../src/learning-store.mjs';
import { postgresDatabase } from '../src/database.mjs';

test('native PostgreSQL upgrade preserves legacy rows; concurrent versions and duplicate notifications are atomic', async t => {
  const f = await fixture({ native: true, learning: true }); t.after(() => f.close());
  const actor = { id: '101', displayName: '山间来客' }, peer = { id: '202', displayName: '观画人' };
  const legacy = await f.store.createPost(actor, randomUUID(), { content: '升级前的交流帖', galleryArtworkId: null }, () => {});
  const body = { requestId: randomUUID(), content: '同一个新的写操作', title: '山水练习', kind: 'discussion', tags: ['笔墨'] };
  const writes = await Promise.all(Array.from({ length: 5 }, () => f.request('/posts', { method: 'POST', payload: body })));
  assert(writes.every(row => row.status === 200));
  assert.equal(new Set(writes.map(row => row.data.result.postId)).size, 1);
  const postId = writes[0].data.result.postId;
  await f.database.query("UPDATE community.posts SET kind='artwork',workflow_status='seeking_feedback' WHERE id=$1", [postId]);
  const comment = await f.store.comment(peer, randomUUID(), postId, { content: '实际点评', dimension: '构图', suggestion: '调整留白' });
  await f.store.adopt(actor, randomUUID(), comment.commentId, true);
  await Promise.all(Array.from({ length: 4 }, () => f.store.generateTasks(actor, randomUUID(), postId)));
  assert.equal((await f.store.growth(postId, actor)).tasks.length, 1);
  // Transactional row lock makes version numbers monotonic across connections.
  const revisions = await Promise.all(Array.from({ length: 3 }, (_, index) => f.store.revision(actor, randomUUID(), postId, { imageIds: [], note: '修改 ' + index, reflection: '' })));
  assert.equal(new Set(revisions.map(row => row.revisionId)).size, 3);
  const growth = await f.store.growth(postId, actor);
  assert.deepEqual(growth.revisions.map(row => row.versionNumber), [2, 3, 4]);
  await Promise.all(Array.from({ length: 5 }, () => f.store.review(actor, randomUUID(), postId, revisions[0].revisionId)));
  assert.equal((await f.store.notifications(peer, 0, 12)).items.filter(row => row.kind === 'review_request').length, 1);
  await f.store.workflow(actor, randomUUID(), postId, 'completed');
  const made = await f.store.buildCase(actor, randomUUID(), postId);
  assert.equal((await f.store.growth(postId, peer)).caseCard, null);
  await f.store.publishCase(actor, randomUUID(), made.caseId, { title: '真实留白案例', summary: '源于实际点评与修改版本', status: 'published' });
  assert.equal((await f.store.cases(0, 12, '留白', '构图')).items.length, 1);
  assert.equal((await f.request('/posts/' + legacy.postId)).data.content, '升级前的交流帖');
  assert.deepEqual((await f.request('/tags')).data.items, [{ tag: '笔墨', count: 1 }]);

  const secondConnection = postgresDatabase(process.env.COMMUNITY_TEST_DATABASE_URL);
  try {
    const restarted = new CommunityStore(secondConnection);
    assert(await restarted.ready());
    assert.equal((await restarted.growth(postId, actor)).revisions.length, 3);
    assert.equal((await restarted.cases(0, 12, '留白', '')).items.length, 1);
  } finally { await secondConnection.close(); }
});
