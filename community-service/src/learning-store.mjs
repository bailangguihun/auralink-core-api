import { randomUUID } from 'node:crypto';
import { CommunityStore as FoundationStore } from './store.mjs';
import { Failure, invalid } from './errors.mjs';
import { imageView } from './learning-contract.mjs';

const iso = value => new Date(value).toISOString();
export class CommunityStore extends FoundationStore {
  learning = false;
  async ready() {
    const rows = (await this.db.query('SELECT version FROM community.schema_version ORDER BY version')).rows;
    this.reference = rows.length === 3 && rows[0].version === 1 && rows[1].version === 2 && rows[2].version === 3;
    this.learning = this.reference || rows.length === 2 && rows[0].version === 1 && rows[1].version === 2;
    return this.learning || rows.length === 1 && rows[0].version === 1;
  }
  async notify(client, recipient, actor, postId, kind) {
    if (String(recipient) === actor.id) return;
    await client.query('INSERT INTO community.notifications(id,user_id,actor_id,post_id,kind) VALUES($1,$2,$3,$4,$5)',
      [randomUUID(), recipient, actor.id, postId, kind]);
  }
  async ownedPost(client, postId, actor) {
    await this.activePost(client, postId, actor?.id || null);
    return (await client.query('SELECT * FROM community.posts WHERE id=$1', [postId])).rows[0];
  }
  async attach(client, actor, postId, imageIds = [], revisionId = null) {
    if (!imageIds.length) return;
    const owned = await client.query('SELECT id FROM community.media_assets WHERE id=ANY($1::uuid[]) AND owner_id=$2', [imageIds, actor.id]);
    if (owned.rows.length !== imageIds.length) throw new Failure(404, 'MEDIA_UNAVAILABLE', '图片不存在或不属于当前账号。');
    for (let index = 0; index < imageIds.length; index++) {
      await client.query('INSERT INTO community.post_images(post_id,asset_id,revision_id,position) VALUES($1,$2,$3,$4)',
        [postId, imageIds[index], revisionId, index]);
    }
  }
  createPost(actor, requestId, body, validateSource) {
    if (!this.learning) return super.createPost(actor, requestId, body, validateSource);
    return this.mutate(actor, requestId, ['post', body], async client => {
      if (body.galleryArtworkId) await validateSource(body.galleryArtworkId);
      const id = randomUUID();
      await client.query('INSERT INTO community.posts(id,author_id,content,gallery_artwork_id,title,kind,creation_stage,creation_goal,help_topics,tags,workflow_status) ' +
        'VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)', [id, actor.id, body.content, body.galleryArtworkId,
        body.title || '', body.kind || 'discussion', body.creationStage || '', body.creationGoal || '', body.helpTopics || [], body.tags || [],
        body.kind === 'artwork' ? 'seeking_feedback' : 'sharing']);
      await this.attach(client, actor, id, body.imageIds);
      return { kind: 'post', postId: id };
    });
  }
  editPost(actor, requestId, postId, body) {
    return this.mutate(actor, requestId, ['edit', postId, body], async client => {
      const post = await this.ownedPost(client, postId, actor);
      if (post.kind === 'artwork' && !body.title) throw invalid('作品名称不能为空。');
      await client.query('UPDATE community.posts SET content=$2,title=$3,tags=$4,creation_goal=$5,updated_at=now() WHERE id=$1',
        [postId, body.content, body.title, body.tags, body.creationGoal]);
      return { kind: 'edit', postId };
    });
  }
  comment(actor, requestId, postId, value) {
    const body = typeof value === 'string' ? { content: value } : value;
    if (!this.learning) return super.comment(actor, requestId, postId, body.content);
    // Legacy receipts used a string command; preserve their exact fingerprint.
    const command = Object.keys(body).length === 1 ? body.content : body;
    return this.mutate(actor, requestId, ['comment', postId, command], async client => {
      const post = await this.ownedPost(client, postId);
      let parent;
      if (body.parentId) {
        parent = (await client.query(`SELECT author_id FROM community.comments WHERE id=$1 AND post_id=$2${this.reference?' AND NOT reference_deleted':''}`, [body.parentId, postId])).rows[0];
        if (!parent) throw invalid('回复的评论不存在。');
      }
      if (body.revisionId && !(await client.query('SELECT id FROM community.artwork_revisions WHERE id=$1 AND post_id=$2', [body.revisionId, postId])).rows.length) throw invalid('作品版本不存在。');
      if (body.dimension && (post.kind !== 'artwork' || body.parentId)) throw invalid('请在作品点评中选择问题维度。');
      if (body.dimension && !body.suggestion) throw invalid('作品点评需要具体的修改建议。');
      const reviewComplete = Boolean(body.revisionId && body.dimension && body.suggestion && actor.id !== String(post.author_id));
      const id = randomUUID();
      await client.query('INSERT INTO community.comments(id,post_id,author_id,content,parent_id,dimension,suggestion,revision_id) VALUES($1,$2,$3,$4,$5,$6,$7,$8)',
        [id, postId, actor.id, body.content, body.parentId || null, body.dimension || null, body.suggestion || '', body.revisionId || null]);
      await this.notify(client, post.author_id, actor, postId, reviewComplete ? 'review_complete' : 'comment');
      if (parent && String(parent.author_id) !== String(post.author_id)) await this.notify(client, parent.author_id, actor, postId, 'reply');
      if (reviewComplete) await client.query("UPDATE community.artwork_revisions SET review_status='completed' WHERE id=$1", [body.revisionId]);
      return { kind: 'comment', postId, commentId: id };
    });
  }
  reaction(actor, requestId, postId, kind, enabled) {
    if (!this.learning) return super.reaction(actor, requestId, postId, kind, enabled);
    return this.mutate(actor, requestId, ['reaction', postId, kind, enabled], async client => {
      const post = await this.ownedPost(client, postId);
      if (enabled) {
        const inserted = await client.query('INSERT INTO community.reactions(post_id,user_id,kind) VALUES($1,$2,$3) ON CONFLICT DO NOTHING RETURNING post_id', [postId, actor.id, kind]);
        if (inserted.rows.length && kind === 'like') await this.notify(client, post.author_id, actor, postId, 'like');
      } else await client.query('DELETE FROM community.reactions WHERE post_id=$1 AND user_id=$2 AND kind=$3', [postId, actor.id, kind]);
      return { kind, postId, enabled };
    });
  }
  async posts(actor, page, size, view, id = null, filters = {}) {
    if (!this.learning) return super.posts(actor, page, size, view, id);
    const result = await this.db.query('SELECT p.*,u.display_name,' +
      `(SELECT count(*)::integer FROM community.comments c WHERE c.post_id=p.id${this.reference?' AND NOT c.reference_deleted':''}) AS comment_count,` +
      "(SELECT count(*)::integer FROM community.reactions r WHERE r.post_id=p.id AND r.kind='like') AS like_count," +
      "EXISTS(SELECT 1 FROM community.reactions r WHERE r.post_id=p.id AND r.user_id=$1 AND r.kind='like') AS liked," +
      "EXISTS(SELECT 1 FROM community.reactions r WHERE r.post_id=p.id AND r.user_id=$1 AND r.kind='favorite') AS favorited " +
      "FROM community.posts p JOIN community.profiles u ON u.user_id=p.author_id WHERE p.status='ACTIVE' " + this.postVisibility('p') +
      "AND ($2::uuid IS NULL OR p.id=$2) AND ($3='all' OR ($3='mine' AND p.author_id=$1) OR ($3='favorites' AND " +
      "EXISTS(SELECT 1 FROM community.reactions r WHERE r.post_id=p.id AND r.user_id=$1 AND r.kind='favorite'))) " +
      "AND ($6='' OR p.content ILIKE '%'||$6||'%' OR p.title ILIKE '%'||$6||'%' OR u.display_name ILIKE '%'||$6||'%') " +
      "AND ($7='' OR $7=ANY(p.tags)) AND ($8='' OR p.kind=$8) AND ($9='' OR p.workflow_status=$9) " +
      "ORDER BY CASE WHEN $10='popular' THEN (SELECT count(*) FROM community.reactions r WHERE r.post_id=p.id AND r.kind='like') ELSE 0 END DESC," +
      'p.created_at DESC,p.id DESC LIMIT $4 OFFSET $5', [actor.id, id, view, size + 1, page * size,
      filters.q || '', filters.tag || '', filters.kind || '', filters.workflow || '', filters.sort || 'latest']);
    const rows = result.rows.slice(0, size);
    if (rows.length) {
      const images = await this.db.query('SELECT i.post_id,i.position,m.* FROM community.post_images i JOIN community.media_assets m ON m.id=i.asset_id ' +
        'WHERE i.post_id=ANY($1::uuid[]) AND i.revision_id IS NULL ORDER BY i.position', [rows.map(row => row.id)]);
      for (const row of rows) row.images = images.rows.filter(image => image.post_id === row.id).map(imageView);
    }
    return { rows, page, hasNext: result.rows.length > size };
  }
  presentPost(row, actor) {
    return { title: row.title || '', kind: row.kind || 'discussion', creationStage: row.creation_stage || '',
      creationGoal: row.creation_goal || '', helpTopics: row.help_topics || [], tags: row.tags || [],
      workflowStatus: row.workflow_status || 'sharing', updatedAt: iso(row.updated_at || row.created_at),
      images: row.images || [], canEdit: String(row.author_id) === actor.id };
  }
  async comments(postId, page, size) {
    if (!this.learning) return super.comments(postId, page, size);
    await this.activeRead(postId);
    const rows = (await this.db.query('SELECT c.*,u.display_name,parent.display_name AS reply_name,r.version_number ' +
      'FROM community.comments c JOIN community.profiles u ON u.user_id=c.author_id ' +
      'LEFT JOIN community.comments pc ON pc.id=c.parent_id LEFT JOIN community.profiles parent ON parent.user_id=pc.author_id ' +
      'LEFT JOIN community.artwork_revisions r ON r.id=c.revision_id ' +
      `WHERE c.post_id=$1${this.reference?' AND NOT c.reference_deleted AND NOT EXISTS(SELECT 1 FROM community.v2_users banned WHERE banned.id=c.author_id AND banned.status<>0)':''} ORDER BY c.created_at,c.id LIMIT $2 OFFSET $3`, [postId, size + 1, page * size])).rows;
    return { page, hasNext: rows.length > size, items: rows.slice(0, size).map(row => this.commentView(row)) };
  }
  commentView(row) {
    return { id: row.id, content: row.content,
      author: { id: String(row.author_id), displayName: row.display_name }, createdAt: iso(row.created_at),
      parentId: row.parent_id, replyName: row.reply_name || null, dimension: row.dimension,
      suggestion: row.suggestion, adopted: row.adopted, revisionId: row.revision_id, revisionNumber: row.version_number || null };
  }
  async commentDetail(commentId) {
    const row = (await this.db.query(`SELECT c.*,u.display_name,r.version_number FROM community.comments c JOIN community.posts p ON p.id=c.post_id AND p.status='ACTIVE' JOIN community.profiles u ON u.user_id=c.author_id LEFT JOIN community.artwork_revisions r ON r.id=c.revision_id WHERE c.id=$1${this.reference?' AND NOT c.reference_deleted AND NOT EXISTS(SELECT 1 FROM community.v2_users banned WHERE banned.id=c.author_id AND banned.status<>0)':''}${this.postVisibility('p')}`, [commentId])).rows[0];
    if (!row) throw new Failure(404, 'COMMENT_UNAVAILABLE', '原点评不存在或已不可查看。');
    return { ...this.commentView(row), postId: row.post_id };
  }
  async activeRead(postId, owner) {
    const row = (await this.db.query(`SELECT * FROM community.posts p WHERE id=$1 AND status='ACTIVE'${this.postVisibility('p')}`, [postId])).rows[0];
    if (!row) throw new Failure(404, 'POST_NOT_FOUND', '帖子不存在或已删除。');
    if (owner && String(row.author_id) !== owner.id) throw new Failure(403, 'POST_FORBIDDEN', '只能修改自己的作品。');
    return row;
  }
  adopt(actor, requestId, commentId, enabled) {
    return this.mutate(actor, requestId, ['adopt', commentId, enabled], async client => {
      const comment = (await client.query('SELECT * FROM community.comments WHERE id=$1', [commentId])).rows[0];
      if (!comment || comment.reference_deleted || !comment.dimension) throw invalid('请选择一条作品点评。');
      await this.ownedPost(client, comment.post_id, actor);
      await client.query('UPDATE community.comments SET adopted=$2 WHERE id=$1', [commentId, enabled]);
      if (enabled && !comment.adopted) await this.notify(client, comment.author_id, actor, comment.post_id, 'adopt');
      return { kind: 'adopt', postId: comment.post_id, commentId, enabled };
    });
  }
  workflow(actor, requestId, postId, status) {
    return this.mutate(actor, requestId, ['workflow', postId, status], async client => {
      const post = await this.ownedPost(client, postId, actor);
      if (post.kind !== 'artwork') throw invalid('自由交流没有作品成长状态。');
      await client.query('UPDATE community.posts SET workflow_status=$2,updated_at=now() WHERE id=$1', [postId, status]);
      return { kind: 'workflow', postId };
    });
  }
  generateTasks(actor, requestId, postId) {
    return this.mutate(actor, requestId, ['tasks', postId], async client => {
      const post = await this.ownedPost(client, postId, actor);
      if (post.kind !== 'artwork') throw invalid();
      const comments = (await client.query("SELECT id,dimension,suggestion FROM community.comments WHERE post_id=$1 AND adopted=true AND suggestion<>'' ORDER BY created_at,id", [postId])).rows;
      if (!comments.length) throw invalid('先采纳包含修改建议的点评，再整理修改任务。');
      for (const row of comments) await client.query('INSERT INTO community.artwork_tasks(id,post_id,source_comment_id,content,dimension) VALUES($1,$2,$3,$4,$5) ON CONFLICT(post_id,source_comment_id) DO NOTHING',
        [randomUUID(), postId, row.id, row.suggestion, row.dimension]);
      return { kind: 'tasks', postId };
    });
  }
  task(actor, requestId, taskId, status) {
    return this.mutate(actor, requestId, ['task', taskId, status], async client => {
      const task = (await client.query('SELECT post_id FROM community.artwork_tasks WHERE id=$1', [taskId])).rows[0];
      if (!task) throw new Failure(404, 'NOT_FOUND', '修改任务不存在。');
      await this.ownedPost(client, task.post_id, actor);
      await client.query('UPDATE community.artwork_tasks SET status=$2 WHERE id=$1', [taskId, status]);
      return { kind: 'task', postId: task.post_id };
    });
  }
  revision(actor, requestId, postId, body) {
    return this.mutate(actor, requestId, ['revision', postId, body], async client => {
      const post = await this.ownedPost(client, postId, actor);
      if (post.kind !== 'artwork') throw invalid();
      const number = Number((await client.query('SELECT COALESCE(max(version_number),1)+1 AS n FROM community.artwork_revisions WHERE post_id=$1', [postId])).rows[0].n);
      const id = randomUUID();
      await client.query('INSERT INTO community.artwork_revisions(id,post_id,version_number,note,reflection) VALUES($1,$2,$3,$4,$5)', [id, postId, number, body.note, body.reflection]);
      await this.attach(client, actor, postId, body.imageIds, id);
      await client.query("UPDATE community.posts SET workflow_status='revising',updated_at=now() WHERE id=$1", [postId]);
      return { kind: 'revision', postId, revisionId: id };
    });
  }
  review(actor, requestId, postId, revisionId) {
    return this.mutate(actor, requestId, ['review', postId, revisionId], async client => {
      await this.ownedPost(client, postId, actor);
      const revision = (await client.query('SELECT review_status FROM community.artwork_revisions WHERE id=$1 AND post_id=$2', [revisionId, postId])).rows[0];
      if (!revision) throw invalid('作品版本不存在。');
      if (revision.review_status === 'requested') return { kind: 'review', postId, revisionId };
      const reviewers = (await client.query('SELECT DISTINCT author_id FROM community.comments WHERE post_id=$1 AND dimension IS NOT NULL AND author_id<>$2', [postId, actor.id])).rows;
      if (!reviewers.length) throw invalid('目前还没有参与点评的同伴，可以先邀请大家留下点评。');
      for (const reviewer of reviewers) await this.notify(client, reviewer.author_id, actor, postId, 'review_request');
      await client.query("UPDATE community.artwork_revisions SET review_status='requested' WHERE id=$1", [revisionId]);
      return { kind: 'review', postId, revisionId };
    });
  }
  async growth(postId, actor) {
    const post = await this.activeRead(postId);
    const [taskResult, revisionResult, imageResult, caseResult] = await Promise.all([
      this.db.query('SELECT * FROM community.artwork_tasks WHERE post_id=$1 ORDER BY created_at,id', [postId]),
      this.db.query('SELECT * FROM community.artwork_revisions WHERE post_id=$1 ORDER BY version_number', [postId]),
      this.db.query('SELECT i.revision_id,i.position,m.* FROM community.post_images i JOIN community.media_assets m ON m.id=i.asset_id WHERE i.post_id=$1 ORDER BY i.position', [postId]),
      this.db.query("SELECT * FROM community.knowledge_cases WHERE post_id=$1 AND (status='published' OR $2::boolean)", [postId, actor.id === String(post.author_id)]),
    ]);
    // Case visibility is decided by the current authenticated author, not caller-supplied IDs.
    const caseRow = caseResult.rows.find(row => row.status === 'published' || actor.id === String(post.author_id));
    return { tasks: taskResult.rows.map(row => ({ id: row.id, content: row.content, dimension: row.dimension, status: row.status, sourceCommentId: row.source_comment_id })),
      revisions: revisionResult.rows.map(row => ({ id: row.id, versionNumber: row.version_number, note: row.note, reflection: row.reflection,
        reviewStatus: row.review_status, createdAt: iso(row.created_at), images: imageResult.rows.filter(image => image.revision_id === row.id).map(imageView) })),
      caseCard: caseRow ? this.caseView(caseRow) : null, source: 'community-records' };
  }
  caseView(row) {
    return { id: row.id, postId: row.post_id, title: row.title, summary: row.summary, lessons: row.lessons,
      dimensions: row.dimensions, status: row.status, updatedAt: iso(row.updated_at), source: 'community-records' };
  }
  buildCase(actor, requestId, postId) {
    return this.mutate(actor, requestId, ['case', postId], async client => {
      const post = await this.ownedPost(client, postId, actor);
      if (post.kind !== 'artwork' || post.workflow_status !== 'completed') throw invalid('先完成作品复盘，再整理成长案例。');
      const comments = (await client.query('SELECT id,dimension,content,suggestion FROM community.comments WHERE post_id=$1 AND adopted=true ORDER BY created_at,id LIMIT 12', [postId])).rows;
      const revisions = (await client.query('SELECT note,reflection FROM community.artwork_revisions WHERE post_id=$1 ORDER BY version_number', [postId])).rows;
      if (!comments.length || !revisions.length) throw invalid('成长案例需要实际采纳的点评和至少一个修改版本。');
      const prior = (await client.query('SELECT id FROM community.knowledge_cases WHERE post_id=$1', [postId])).rows[0];
      const id = prior?.id || randomUUID();
      if (!prior) await client.query('INSERT INTO community.knowledge_cases(id,post_id,title,summary,lessons,dimensions) VALUES($1,$2,$3,$4,$5::jsonb,$6)',
        [id, postId, `${post.title} · 成长复盘`.slice(0, 160), (post.creation_goal + '\n' + revisions.map(row => row.note + (row.reflection ? '\n' + row.reflection : '')).join('\n')).slice(0, 3000),
        JSON.stringify(comments.map(row => ({ dimension: row.dimension, observation: row.content, suggestion: row.suggestion, commentId: row.id }))), [...new Set(comments.map(row => row.dimension))]]);
      return { kind: 'case', postId, caseId: id };
    });
  }
  publishCase(actor, requestId, caseId, body) {
    return this.mutate(actor, requestId, ['case-edit', caseId, body], async client => {
      const row = (await client.query('SELECT post_id FROM community.knowledge_cases WHERE id=$1', [caseId])).rows[0];
      if (!row) throw new Failure(404, 'NOT_FOUND', '案例不存在。');
      await this.ownedPost(client, row.post_id, actor);
      await client.query("UPDATE community.knowledge_cases SET title=$2,summary=$3,status=$4::text,updated_at=now(),published_at=CASE WHEN $4::text='published' THEN now() ELSE NULL END WHERE id=$1", [caseId, body.title, body.summary, body.status]);
      return { kind: 'case-edit', postId: row.post_id, caseId };
    });
  }
  async cases(page, size, query, dimension) {
    const result = await this.db.query("SELECT c.* FROM community.knowledge_cases c JOIN community.posts p ON p.id=c.post_id WHERE c.status='published' AND p.status='ACTIVE' " + this.postVisibility('p') +
      "AND ($3='' OR c.title ILIKE '%'||$3||'%' OR c.summary ILIKE '%'||$3||'%') AND ($4='' OR $4=ANY(c.dimensions)) ORDER BY c.published_at DESC,c.id DESC LIMIT $1 OFFSET $2", [size + 1, page * size, query, dimension]);
    return { items: result.rows.slice(0, size).map(row => this.caseView(row)), page, hasNext: result.rows.length > size };
  }
  async notifications(actor, page, size) {
    const [result, unread] = await Promise.all([
      this.db.query(`SELECT n.*,u.display_name,p.title FROM community.notifications n JOIN community.posts p ON p.id=n.post_id AND p.status='ACTIVE' JOIN community.profiles u ON u.user_id=n.actor_id WHERE n.user_id=$1${this.postVisibility('p')} ORDER BY n.created_at DESC,n.id DESC LIMIT $2 OFFSET $3`, [actor.id, size + 1, page * size]),
      this.db.query(`SELECT count(*)::integer AS n FROM community.notifications n JOIN community.posts p ON p.id=n.post_id AND p.status='ACTIVE' WHERE n.user_id=$1 AND n.read_at IS NULL${this.postVisibility('p')}`, [actor.id]),
    ]);
    return { items: result.rows.slice(0, size).map(row => ({ id: row.id, postId: row.post_id, kind: row.kind,
      actorName: row.display_name, postTitle: row.title, read: !!row.read_at, createdAt: iso(row.created_at) })), unread: unread.rows[0].n, page, hasNext: result.rows.length > size };
  }
  readNotifications(actor, requestId, notificationId) {
    return this.mutate(actor, requestId, ['read', notificationId], async client => {
      const row = (await client.query('UPDATE community.notifications SET read_at=COALESCE(read_at,now()) WHERE id=$1 AND user_id=$2 RETURNING post_id', [notificationId, actor.id])).rows[0];
      if (!row) throw new Failure(404, 'NOT_FOUND', '通知不存在。');
      return { kind: 'read', postId: row.post_id };
    });
  }
  async tags() {
    return (await this.db.query("SELECT tag,count(*)::integer AS count FROM community.posts p CROSS JOIN unnest(p.tags) AS tag WHERE p.status='ACTIVE' GROUP BY tag ORDER BY count(*) DESC,tag LIMIT 20")).rows;
  }
  async mediaFor(actor, id) {
    const row = (await this.db.query(`SELECT m.* FROM community.media_assets m WHERE m.id=$1 AND (m.owner_id=$2 OR EXISTS(SELECT 1 FROM community.post_images i JOIN community.posts p ON p.id=i.post_id AND p.status='ACTIVE' WHERE i.asset_id=m.id${this.postVisibility('p')}))`, [id, actor.id])).rows[0];
    if (!row) throw new Failure(404, 'MEDIA_UNAVAILABLE', '图片不存在或不可访问。');
    return row;
  }
}
