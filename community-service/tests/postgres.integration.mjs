import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture } from './support.mjs';
import { postgresDatabase } from '../src/database.mjs';
import { CommunityStore } from '../src/store.mjs';

test('native PostgreSQL multi-connection idempotency and rollback', async t => {
  const f = await fixture({ native: true }); t.after(() => f.close());
  const payload = { requestId: randomUUID(), content: '同一次并发提交' };
  const replies = await Promise.all(Array.from({ length: 5 }, () => f.request('/posts', { method: 'POST', payload })));
  assert(replies.every(r => r.status === 200));
  assert.equal(new Set(replies.map(r => r.data.result.postId)).size, 1);
  const postId = replies[0].data.result.postId;
  const key = randomUUID();
  const conflict = await Promise.all(['one', 'two'].map(content => f.request('/posts/' + postId + '/comments', {
    method: 'POST', payload: { requestId: key, content },
  })));
  assert.deepEqual(conflict.map(r => r.status).sort(), [200, 409]);
  assert.equal((await f.database.query('SELECT count(*)::int AS count FROM community.comments')).rows[0].count, 1);
  const actor = { id: '101', displayName: 'test' }, failed = randomUUID();
  await assert.rejects(f.store.mutate(actor, failed, ['rollback'], async c => {
    await c.query("INSERT INTO community.posts(id,author_id,content) VALUES($1,$2,'rollback')", [randomUUID(), actor.id]);
    throw new Error('fail before commit');
  }));
  assert.equal((await f.store.outcome(actor, failed)).state, 'NOT_OBSERVED');
  assert.equal((await f.database.query('SELECT count(*)::int AS count FROM community.posts')).rows[0].count, 1);

  await t.test('runtime uses a separate least-privilege role and cannot change existing platform tables', async () => {
    await assert.rejects(f.database.verifyRuntimeRole(), /non-DDL role/);
    await f.database.query("CREATE ROLE community_runtime_fixture LOGIN PASSWORD 'disposable_runtime_only'");
    await f.database.query('GRANT USAGE ON SCHEMA community TO community_runtime_fixture');
    await f.database.query('GRANT SELECT ON ALL TABLES IN SCHEMA community TO community_runtime_fixture');
    await f.database.query('GRANT INSERT,UPDATE ON community.profiles,community.posts,community.operations TO community_runtime_fixture');
    await f.database.query('GRANT INSERT ON community.comments TO community_runtime_fixture');
    await f.database.query('GRANT INSERT,DELETE ON community.reactions TO community_runtime_fixture');
    await f.database.query("CREATE TABLE public.platform_fixture(id int PRIMARY KEY, preserved text)");
    await f.database.query("INSERT INTO public.platform_fixture VALUES(1,'unchanged')");
    const url = new URL(process.env.COMMUNITY_TEST_DATABASE_URL);
    url.username = 'community_runtime_fixture'; url.password = 'disposable_runtime_only';
    const restricted = postgresDatabase(url.href);
    try {
      await restricted.verifyRuntimeRole();
      const store = new CommunityStore(restricted);
      assert(await store.ready());
      const made = await store.createPost(actor, randomUUID(), { content: 'limited runtime', galleryArtworkId: null }, () => {});
      await store.comment(actor, randomUUID(), made.postId, 'allowed');
      await store.reaction(actor, randomUUID(), made.postId, 'like', true);
      await store.reaction(actor, randomUUID(), made.postId, 'like', false);
      await store.remove(actor, randomUUID(), made.postId);
      await assert.rejects(restricted.query('DELETE FROM community.operations'), /permission denied/);
      await assert.rejects(restricted.query('CREATE TABLE community.forbidden(id int)'), /permission denied/);
      await assert.rejects(restricted.query("UPDATE public.platform_fixture SET preserved='bad'"), /permission denied/);
      await f.database.query('GRANT UPDATE ON public.platform_fixture TO community_runtime_fixture');
      await assert.rejects(restricted.verifyRuntimeRole(), /other schemas/);
      await f.database.query('REVOKE UPDATE ON public.platform_fixture FROM community_runtime_fixture');
      assert.equal((await f.database.query('SELECT preserved FROM public.platform_fixture')).rows[0].preserved, 'unchanged');
    } finally { await restricted.close(); }
  });
});
