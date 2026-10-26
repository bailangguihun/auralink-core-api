import { createHash, randomUUID } from 'node:crypto';
import { Failure } from './errors.mjs';

export class CommunityStore {
  constructor(database) { this.db = database; }
  async ready() {
    const result = await this.db.query('SELECT version FROM community.schema_version ORDER BY version');
    return result.rows.length === 1 && result.rows[0].version === 1;
  }
  async outcome(actor, requestId) {
    const result = await this.db.query(
      'SELECT result FROM community.operations WHERE user_id=$1 AND request_id=$2', [actor.id, requestId]);
    return result.rows[0]?.result
      ? { state: 'COMMITTED', result: result.rows[0].result }
      : { state: 'NOT_OBSERVED', result: null };
  }
  async mutate(actor, requestId, command, work) {
    const fingerprint = createHash('sha256').update(JSON.stringify(command)).digest('hex');
    return this.db.transaction(async client => {
      await client.query('SET LOCAL statement_timeout = 5000');
      await client.query('SET LOCAL lock_timeout = 2000');
      await client.query(
        'INSERT INTO community.operations(user_id,request_id,fingerprint) VALUES($1,$2,$3) ON CONFLICT DO NOTHING',
        [actor.id, requestId, fingerprint]);
      const row = (await client.query(
        'SELECT fingerprint,result FROM community.operations WHERE user_id=$1 AND request_id=$2 FOR UPDATE',
        [actor.id, requestId])).rows[0];
      if (row.fingerprint !== fingerprint) throw new Failure(409, 'REQUEST_ID_REUSED', '同一操作标识不能用于不同内容。');
      if (row.result) return row.result;
      await client.query(
        'INSERT INTO community.profiles(user_id,display_name) VALUES($1,$2) ON CONFLICT(user_id) DO UPDATE SET display_name=EXCLUDED.display_name',
        [actor.id, actor.displayName]);
      const result = await work(client);
      await client.query(
        'UPDATE community.operations SET result=$3::jsonb WHERE user_id=$1 AND request_id=$2',
        [actor.id, requestId, JSON.stringify(result)]);
      return result;
    });
  }
  async activePost(client, id, owner = null) {
    const row = (await client.query("SELECT id,author_id FROM community.posts WHERE id=$1 AND status='ACTIVE' FOR UPDATE", [id])).rows[0];
    if (!row) throw new Failure(404, 'POST_NOT_FOUND', '帖子不存在或已删除。');
    if (owner && String(row.author_id) !== owner) throw new Failure(403, 'POST_FORBIDDEN', '只能删除自己的帖子。');
    return row;
  }
  createPost(actor, requestId, body, validateSource) {
    return this.mutate(actor, requestId, ['post', body], async client => {
      // Idempotent committed replay does not call the gallery or make a new post.
      if (body.galleryArtworkId) await validateSource(body.galleryArtworkId);
      const id = randomUUID();
      await client.query('INSERT INTO community.posts(id,author_id,content,gallery_artwork_id) VALUES($1,$2,$3,$4)',
        [id, actor.id, body.content, body.galleryArtworkId]);
      return { kind: 'post', postId: id };
    });
  }
  comment(actor, requestId, postId, text) {
    return this.mutate(actor, requestId, ['comment', postId, text], async client => {
      await this.activePost(client, postId);
      const id = randomUUID();
      await client.query('INSERT INTO community.comments(id,post_id,author_id,content) VALUES($1,$2,$3,$4)', [id, postId, actor.id, text]);
      return { kind: 'comment', postId, commentId: id };
    });
  }
  reaction(actor, requestId, postId, kind, enabled) {
    return this.mutate(actor, requestId, ['reaction', postId, kind, enabled], async client => {
      await this.activePost(client, postId);
      if (enabled) await client.query('INSERT INTO community.reactions(post_id,user_id,kind) VALUES($1,$2,$3) ON CONFLICT DO NOTHING', [postId, actor.id, kind]);
      else await client.query('DELETE FROM community.reactions WHERE post_id=$1 AND user_id=$2 AND kind=$3', [postId, actor.id, kind]);
      return { kind, postId, enabled };
    });
  }
  remove(actor, requestId, postId) {
    return this.mutate(actor, requestId, ['delete', postId], async client => {
      await this.activePost(client, postId, actor.id);
      await client.query("UPDATE community.posts SET status='DELETED' WHERE id=$1", [postId]);
      return { kind: 'delete', postId };
    });
  }
  async posts(actor, page, size, view, id = null) {
    const result = await this.db.query(
      "SELECT p.*,u.display_name,(SELECT count(*)::integer FROM community.comments c WHERE c.post_id=p.id) AS comment_count," +
      "(SELECT count(*)::integer FROM community.reactions r WHERE r.post_id=p.id AND r.kind='like') AS like_count," +
      "EXISTS(SELECT 1 FROM community.reactions r WHERE r.post_id=p.id AND r.user_id=$1 AND r.kind='like') AS liked," +
      "EXISTS(SELECT 1 FROM community.reactions r WHERE r.post_id=p.id AND r.user_id=$1 AND r.kind='favorite') AS favorited " +
      "FROM community.posts p JOIN community.profiles u ON u.user_id=p.author_id WHERE p.status='ACTIVE' " +
      "AND ($2::uuid IS NULL OR p.id=$2) AND ($3='all' OR ($3='mine' AND p.author_id=$1) OR " +
      "($3='favorites' AND EXISTS(SELECT 1 FROM community.reactions r WHERE r.post_id=p.id AND r.user_id=$1 AND r.kind='favorite'))) " +
      'ORDER BY p.created_at DESC,p.id DESC LIMIT $4 OFFSET $5', [actor.id, id, view, size + 1, page * size]);
    return { rows: result.rows.slice(0, size), page, hasNext: result.rows.length > size };
  }
  async comments(postId, page, size) {
    // A separate existence check avoids a write lock on a read-only endpoint.
    const post = await this.db.query("SELECT id FROM community.posts WHERE id=$1 AND status='ACTIVE'", [postId]);
    if (!post.rows.length) throw new Failure(404, 'POST_NOT_FOUND', '帖子不存在或已删除。');
    const result = await this.db.query(
      'SELECT c.*,u.display_name FROM community.comments c JOIN community.profiles u ON u.user_id=c.author_id ' +
      "JOIN community.posts p ON p.id=c.post_id AND p.status='ACTIVE' WHERE c.post_id=$1 ORDER BY c.created_at,c.id LIMIT $2 OFFSET $3",
      [postId, size + 1, page * size]);
    return {
      items: result.rows.slice(0, size).map(row => ({ id: row.id, content: row.content,
        author: { id: String(row.author_id), displayName: row.display_name }, createdAt: new Date(row.created_at).toISOString() })),
      page, hasNext: result.rows.length > size,
    };
  }
}
