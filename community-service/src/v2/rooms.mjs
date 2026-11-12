import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import sharp from 'sharp';
import { WebSocketServer } from 'ws';
const ROOM_ROLES = new Set(['visitor', 'member', 'owner']);
const SHAPES = new Set(['rectangle', 'ellipse', 'freehand']);
const BRUSHES = new Set(['ink', 'liner', 'pencil', 'marker', 'dry']);
const TIP_MODES = new Set(['auto', 'device', 'fixed']);
const ROOM_STATUSES = new Set(['active', 'ended', 'closed']);
export async function createRoomFeature({
  db,
  uploadDir,
  readBody,
  receiveUpload,
  currentUser,
  currentMediaUser,
  ensureCoinWallet,
  roomSpendableCoins,
  applyCoinChange,
  generateArtworkCritique,
  consumeTicket,
  socketWork
}) {
  const socketsByRoom = new Map();
  const connections = new Set();
  const shareDisconnectTimers = new Map();
  const shareAnnotationPreviews = new Map();
  const voiceByRoom = new Map();
  const VOICE_SPEAKER_LIMIT = 6;
  function voiceEvent(ws, eventType, data) {
    if (ws.readyState === 1) ws.send(JSON.stringify({
      eventId: id('evt'),
      eventType,
      roomId: ws.roomId,
      actorId: ws.userId,
      serverTime: now(),
      data
    }));
  }
  function voiceRoster(roomId) {
    return [...(voiceByRoom.get(roomId)?.values() || [])].map(peer => ({
      peerId: peer.id,
      userId: peer.ws.userId,
      speaking: peer.speaking
    }));
  }
  function leaveVoice(ws) {
    const roomId = ws.roomId,
      peer = ws.voicePeer;
    if (!roomId || !peer) return;
    const peers = voiceByRoom.get(roomId);
    peers?.delete(peer.id);
    if (peers && !peers.size) voiceByRoom.delete(roomId);
    ws.voicePeer = null;
    broadcast(roomId, 'voice.left', {
      peerId: peer.id,
      userId: ws.userId
    }, ws.userId);
  }
  function stopVoiceForUser(roomId, userId, reason = 'permission_changed') {
    let stopped = 0;
    for (const peer of voiceByRoom.get(roomId)?.values() || []) if (peer.ws.userId === userId && peer.speaking) {
      peer.speaking = false;
      stopped++;
      voiceEvent(peer.ws, 'voice.forced_stop', {
        reason
      });
      broadcast(roomId, 'voice.updated', {
        peerId: peer.id,
        userId,
        speaking: false
      }, userId);
    }
    return stopped;
  }
  function stopRoomVoice(roomId) {
    for (const peer of [...(voiceByRoom.get(roomId)?.values() || [])]) {
      voiceEvent(peer.ws, 'voice.room_ended', {});
      leaveVoice(peer.ws);
    }
  }
  try {} catch {}
  try {} catch {}
  try {} catch {}
  const now = () => new Date().toISOString();
  const id = prefix => `${prefix}_${crypto.randomUUID()}`;
  const inviteCode = () => crypto.randomBytes(18).toString('base64url');
  const json = (value, fallback = {}) => {
    try {
      return JSON.parse(value);
    } catch {
      return fallback;
    }
  };
  const userView = user => user ? {
    id: user.id,
    username: user.username,
    nickname: user.nickname,
    avatar: user.avatar,
    role: user.role,
    expertVerified: Boolean(user.expert_verified)
  } : null;
  const send = (res, status, data = null, message = '') => {
    res.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8'
    });
    res.end(JSON.stringify({
      success: status < 400,
      data,
      message,
      requestId: id('req')
    }));
  };
  const fail = (res, status, code, message, details = null) => {
    res.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8'
    });
    res.end(JSON.stringify({
      success: false,
      error: {
        code,
        message,
        details
      },
      message,
      requestId: id('req')
    }));
  };
  const requireUser = (user, res) => user || (fail(res, 401, 'AUTH_REQUIRED', '请先登录'), null);
  const roomById = async roomId => await db.prepare('SELECT * FROM rooms WHERE id=?').get(roomId);
  const memberById = async (roomId, userId) => await db.prepare('SELECT * FROM room_members WHERE room_id=? AND user_id=?').get(roomId, userId);
  const activeShare = async roomId => await db.prepare("SELECT * FROM room_view_shares WHERE room_id=? AND status='active'").get(roomId);
  const shareView = share => share ? {
    roomId: share.room_id,
    presenterId: share.presenter_id,
    sessionId: share.session_id,
    viewport: json(share.viewport),
    selectedAnnotationId: share.selected_annotation_id,
    strokes: json(share.strokes, []),
    startedAt: share.started_at
  } : null;
  const shareRequests = async roomId => await db.prepare('SELECT r.user_id AS userId,u.nickname,r.created_at AS createdAt FROM room_share_requests r JOIN users u ON u.id=r.user_id WHERE r.room_id=? ORDER BY r.created_at').all(roomId);
  const isMuted = member => Boolean(member?.muted_until && new Date(member.muted_until).getTime() > Date.now());
  const activeMember = async (roomId, userId) => {
    const member = await memberById(roomId, userId);
    return member?.status === 'active' ? member : null;
  };
  const canWrite = member => member && ['member', 'owner'].includes(member.role) && !isMuted(member);
  const requireRoom = async (roomId, res) => (await roomById(roomId)) || (fail(res, 404, 'ROOM_NOT_FOUND', '房间不存在'), null);
  const requireMember = async (roomId, user, res) => (await activeMember(roomId, user.id)) || (fail(res, 403, 'ROOM_MEMBERSHIP_REQUIRED', '请先加入房间'), null);
  const requireOwner = (room, user, res) => room.owner_id === user.id || user.role === 'admin' || (fail(res, 403, 'ROOM_OWNER_REQUIRED', '仅房主可执行此操作'), false);
  const assertActive = (room, res) => room.status === 'active' || (fail(res, 409, 'ROOM_NOT_ACTIVE', '房间当前为只读状态'), false);
  const touchRoom = async roomId => await db.prepare('UPDATE rooms SET updated_at=?,last_activity_at=? WHERE id=?').run(now(), now(), roomId);
  const audit = async (roomId, actorId, action, {
    targetUserId = null,
    targetType = null,
    targetId = null,
    reason = null,
    details = {}
  } = {}) => {
    await db.prepare('INSERT INTO room_moderation_actions(id,room_id,actor_id,target_user_id,target_type,target_id,action,reason,details,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)').run(id('audit'), roomId, actorId, targetUserId, targetType, targetId, action, reason, JSON.stringify(details), now());
  };
  async function refundPayment(payment) {
    if (!payment || payment.status === 'refunded') return 0;
    await ensureCoinWallet(payment.payer_id);
    await ensureCoinWallet(payment.recipient_id);
    if (payment.status === 'settled') {
      const available = (await db.prepare('SELECT balance FROM coin_wallets WHERE user_id=?').get(payment.recipient_id)).balance;
      const debit = Math.min(available, payment.amount);
      if (debit) await applyCoinChange(payment.recipient_id, -debit, {
        entryType: 'spend',
        sourceType: 'room_refund_debit',
        sourceId: payment.id,
        description: '退还房间门票',
        idempotencyKey: `room:refund:host:${payment.id}`
      });
      if (debit < payment.amount) await db.prepare('INSERT INTO room_income_debts(user_id,amount) VALUES (?,?) ON CONFLICT(user_id) DO UPDATE SET amount=amount+excluded.amount').run(payment.recipient_id, payment.amount - debit);
    }
    await applyCoinChange(payment.payer_id, payment.amount, {
      entryType: 'refund',
      sourceType: 'room_refund',
      sourceId: payment.id,
      description: '房间门票已退还',
      idempotencyKey: `room:refund:payer:${payment.id}`
    });
    await db.prepare("UPDATE room_entry_payments SET status='refunded',refunded_at=? WHERE id=?").run(now(), payment.id);
    return payment.amount;
  }
  async function settleDuePayments(userId) {
    const rows = await db.prepare("SELECT p.* FROM room_entry_payments p JOIN rooms r ON r.id=p.room_id JOIN users u ON u.id=p.recipient_id JOIN room_members m ON m.room_id=p.room_id AND m.user_id=p.payer_id WHERE p.recipient_id=? AND p.status='pending' AND p.settle_after<=? AND r.status='active' AND u.expert_verified=1 AND u.status=0 AND m.status='active' ORDER BY p.settle_after LIMIT 100").all(userId, now());
    let settled = 0;
    for (const payment of rows) {
      await ensureCoinWallet(payment.recipient_id);
      await db.exec('BEGIN IMMEDIATE');
      try {
        const debt = Number((await db.prepare('SELECT amount FROM room_income_debts WHERE user_id=?').get(payment.recipient_id))?.amount || 0);
        const withheld = Math.min(debt, payment.amount);
        const payout = payment.amount - withheld;
        if (withheld) await db.prepare('UPDATE room_income_debts SET amount=amount-? WHERE user_id=?').run(withheld, payment.recipient_id);
        if (payout) await applyCoinChange(payment.recipient_id, payout, {
          entryType: 'earn',
          sourceType: 'room_income',
          sourceId: payment.id,
          description: '墨币房间门票收入',
          idempotencyKey: `room:income:${payment.id}`
        });
        await db.prepare("UPDATE room_entry_payments SET status='settled',settled_at=? WHERE id=? AND status='pending'").run(now(), payment.id);
        await db.exec('COMMIT');
        settled += payment.amount;
      } catch (error) {
        await db.exec('ROLLBACK');
        throw error;
      }
    }
    return settled;
  }
  async function incomeSummary(userId) {
    await settleDuePayments(userId);
    const pending = (await db.prepare("SELECT COALESCE(SUM(amount),0) AS n FROM room_entry_payments WHERE recipient_id=? AND status='pending'").get(userId)).n;
    const settled = (await db.prepare("SELECT COALESCE(SUM(amount),0) AS n FROM room_entry_payments WHERE recipient_id=? AND status='settled'").get(userId)).n;
    const refundDebt = Number((await db.prepare('SELECT amount FROM room_income_debts WHERE user_id=?').get(userId))?.amount || 0);
    return {
      pending: Number(pending || 0),
      settled: Number(settled || 0),
      refundDebt
    };
  }
  async function adminPaymentList() {
    return await db.prepare("SELECT p.id,p.room_id AS roomId,r.name AS roomName,p.payer_id AS payerId,p.recipient_id AS recipientId,p.amount,p.status,p.created_at AS createdAt,p.settle_after AS settleAfter FROM room_entry_payments p JOIN rooms r ON r.id=p.room_id ORDER BY p.created_at DESC LIMIT 100").all();
  }
  async function adminRefundPayment(paymentId, adminId) {
    const payment = await db.prepare('SELECT * FROM room_entry_payments WHERE id=?').get(paymentId);
    if (!payment) return null;
    await ensureCoinWallet(payment.payer_id);
    await ensureCoinWallet(payment.recipient_id);
    await db.exec('BEGIN IMMEDIATE');
    try {
      const amount = await refundPayment(payment);
      if (amount) await db.prepare("UPDATE room_members SET status='removed',removed_until=NULL,updated_at=? WHERE room_id=? AND user_id=? AND role<>'owner'").run(now(), payment.room_id, payment.payer_id);
      await db.exec('COMMIT');
      if (amount) {
        await audit(payment.room_id, adminId, 'payment.admin_refunded', {
          targetUserId: payment.payer_id,
          targetType: 'room_payment',
          targetId: payment.id,
          details: {
            amount
          }
        });
        disconnectUser(payment.room_id, payment.payer_id);
      }
      return {
        id: payment.id,
        refunded: amount
      };
    } catch (error) {
      await db.exec('ROLLBACK');
      throw error;
    }
  }
  async function adminCloseRoom(roomId, adminId) {
    const room = await roomById(roomId);
    if (!room || room.entry_price === 0) return null;
    if (await activeShare(roomId)) await stopShare(roomId, adminId, 'room_closed');
    const pending = await db.prepare("SELECT * FROM room_entry_payments WHERE room_id=? AND status='pending'").all(roomId);
    for (const payment of pending) await ensureCoinWallet(payment.payer_id);
    await db.exec('BEGIN IMMEDIATE');
    try {
      for (const payment of pending) {
        await refundPayment(payment);
        await db.prepare("UPDATE room_members SET status='removed',removed_until=NULL,updated_at=? WHERE room_id=? AND user_id=? AND role<>'owner'").run(now(), roomId, payment.payer_id);
      }
      await db.prepare("UPDATE rooms SET status='closed',updated_at=? WHERE id=?").run(now(), roomId);
      await db.exec('COMMIT');
    } catch (error) {
      await db.exec('ROLLBACK');
      throw error;
    }
    for (const payment of pending) disconnectUser(roomId, payment.payer_id);
    stopRoomVoice(roomId);
    await audit(roomId, adminId, 'room.admin_closed', {
      details: {
        refundedPending: pending.length
      }
    });
    broadcast(roomId, 'room.ended', await roomView(await roomById(roomId)), adminId);
    return {
      roomId,
      refundedPending: pending.length
    };
  }
  async function roomView(room, viewerId = 0) {
    const owner = await db.prepare('SELECT * FROM users WHERE id=?').get(room.owner_id);
    const member = viewerId ? await memberById(room.id, viewerId) : null;
    const counts = await db.prepare("SELECT COUNT(*) AS total,SUM(CASE WHEN role='member' THEN 1 ELSE 0 END) AS members,SUM(CASE WHEN role='visitor' THEN 1 ELSE 0 END) AS visitors FROM room_members WHERE room_id=? AND status='active'").get(room.id);
    const canViewArtwork = room.entry_price === 0 || Boolean(member?.status === 'active');
    let currentSession = null;
    if (room.current_session_id && canViewArtwork) {
      const row = await db.prepare('SELECT s.*,a.url,a.preview_url,a.thumbnail_url,a.width,a.height,a.filename,a.source_type,a.gallery_artwork_id FROM artwork_sessions s JOIN artwork_assets a ON a.id=s.asset_id WHERE s.id=?').get(room.current_session_id);
      if (row) currentSession = sessionView(row, room);
    }
    const autoArtworkCover = room.entry_price > 0 && Boolean(await db.prepare('SELECT 1 FROM artwork_assets a JOIN artwork_sessions s ON s.asset_id=a.id WHERE s.room_id=? AND a.thumbnail_url=? LIMIT 1').get(room.id, room.cover_url));
    return {
      id: room.id,
      name: room.name,
      description: room.description,
      coverUrl: autoArtworkCover ? null : room.cover_url,
      visibility: room.visibility,
      memberLimit: room.member_limit,
      allowMemberInvites: Boolean(room.allow_member_invites),
      status: room.status,
      entryPrice: room.entry_price || 0,
      entryPaused: Boolean(room.entry_price > 0 && !owner?.expert_verified),
      owner: userView(owner),
      currentSession,
      memberCount: Number(counts.total || 0),
      onlineCount: onlineCount(room.id),
      myMembership: member ? await memberView(member) : null,
      createdAt: room.created_at,
      updatedAt: room.updated_at,
      lastActivityAt: room.last_activity_at
    };
  }
  async function memberView(member) {
    const user = await db.prepare('SELECT * FROM users WHERE id=?').get(member.user_id);
    return {
      user: userView(user),
      role: member.role,
      status: member.status,
      mutedUntil: member.muted_until,
      removedUntil: member.removed_until,
      joinedAt: member.joined_at,
      online: isOnline(member.room_id, member.user_id)
    };
  }
  function sessionView(row, room = null) {
    const paid = Boolean(room?.entry_price > 0);
    const mediaUrl = variant => `/api/v2/rooms/${encodeURIComponent(row.room_id)}/artwork-assets/${encodeURIComponent(row.asset_id)}/file?variant=${variant}`;
    return {
      id: row.id,
      roomId: row.room_id,
      title: row.title,
      visitorVisible: Boolean(row.visitor_visible),
      status: row.status,
      startedAt: row.started_at,
      endedAt: row.ended_at,
      asset: {
        id: row.asset_id,
        sourceType: row.source_type,
        galleryArtworkId: row.gallery_artwork_id,
        filename: row.filename,
        url: paid ? mediaUrl('display') : row.url,
        previewUrl: paid ? mediaUrl('preview') : row.preview_url,
        thumbnailUrl: paid ? mediaUrl('thumbnail') : row.thumbnail_url,
        width: row.width,
        height: row.height
      }
    };
  }
  async function annotationView(row, viewer) {
    const author = await db.prepare('SELECT * FROM users WHERE id=?').get(row.author_id);
    const maySeeHidden = viewer && (viewer.id === row.author_id || viewer.id === (await roomById(row.room_id))?.owner_id || viewer.role === 'admin');
    if (row.status === 'hidden' && !maySeeHidden) return null;
    return {
      id: row.id,
      roomId: row.room_id,
      sessionId: row.session_id,
      author: userView(author),
      shapeType: row.shape_type,
      geometry: json(row.geometry),
      content: row.content,
      status: row.status,
      sourceType: row.source_type || 'human',
      aiMetadata: json(row.ai_metadata, null),
      version: row.version,
      createdAt: row.created_at,
      updatedAt: row.updated_at
    };
  }
  async function drawingView(row, viewer) {
    const author = await db.prepare('SELECT * FROM users WHERE id=?').get(row.author_id);
    const maySeeHidden = viewer && (viewer.id === row.author_id || viewer.id === (await roomById(row.room_id))?.owner_id || viewer.role === 'admin');
    if (row.status === 'hidden' && !maySeeHidden) return null;
    return {
      id: row.id,
      roomId: row.room_id,
      sessionId: row.session_id,
      author: userView(author),
      strokes: json(row.strokes, []),
      status: row.status,
      version: row.version,
      createdAt: row.created_at,
      updatedAt: row.updated_at
    };
  }
  async function messageView(row) {
    const author = await db.prepare('SELECT * FROM users WHERE id=?').get(row.author_id);
    return {
      id: row.id,
      roomId: row.room_id,
      author: userView(author),
      content: row.status === 'deleted' ? '该消息已被删除' : row.content,
      status: row.status,
      createdAt: row.created_at
    };
  }
  function onlineCount(roomId) {
    return socketsByRoom.get(roomId)?.size || 0;
  }
  function isOnline(roomId, userId) {
    return [...(socketsByRoom.get(roomId) || [])].some(ws => ws.userId === userId && ws.readyState === 1);
  }
  function broadcast(roomId, eventType, data, actorId = null) {
    const event = JSON.stringify({
      eventId: id('evt'),
      eventType,
      roomId,
      actorId,
      serverTime: now(),
      data
    });
    for (const ws of socketsByRoom.get(roomId) || []) if (ws.readyState === 1) ws.send(event);
  }
  async function broadcastContent(roomId, eventType, data, actorId = null) {
    const [room, session, members] = await Promise.all([
      roomById(roomId),
      db.prepare('SELECT status,visitor_visible FROM artwork_sessions WHERE id=? AND room_id=?').get(data.sessionId, roomId),
      db.prepare('SELECT m.user_id,m.role,m.status,u.role AS account_role,u.status AS account_status FROM room_members m JOIN users u ON u.id=m.user_id WHERE m.room_id=?').all(roomId)
    ]);
    const permissions = new Map(members.map(member => [member.user_id, member]));
    const removedType = eventType.startsWith('drawing.') ? 'drawing.deleted' : 'annotation.deleted';
    const eventId = id('evt'), serverTime = now();
    for (const ws of socketsByRoom.get(roomId) || []) {
      if (ws.readyState !== 1) continue;
      const member = permissions.get(ws.userId);
      if (!member || member.status !== 'active' || member.account_status !== 0) {
        ws.close(4403, '房间访问权限已失效');
        continue;
      }
      const historyVisible = session && (member.role !== 'visitor' || session.status === 'active' || session.visitor_visible === 1);
      const hiddenVisible = data.status !== 'hidden' || ws.userId === data.author?.id || ws.userId === room?.owner_id || member.account_role === 'admin';
      const permitted = Boolean(historyVisible && hiddenVisible);
      ws.send(JSON.stringify({ eventId, eventType: permitted ? eventType : removedType, roomId, actorId, serverTime,
        data: permitted ? data : { id: data.id } }));
    }
  }
  function broadcastPresence(roomId) {
    broadcast(roomId, 'presence.updated', {
      onlineCount: onlineCount(roomId)
    });
  }
  function validGeometry(shapeType, geometry) {
    if (!geometry || typeof geometry !== 'object') return false;
    const validNumber = value => Number.isFinite(Number(value)) && Number(value) >= 0 && Number(value) <= 1;
    if (![geometry.x, geometry.y, geometry.width, geometry.height].every(validNumber)) return false;
    if (Number(geometry.width) <= 0 || Number(geometry.height) <= 0 || Number(geometry.x) + Number(geometry.width) > 1.001 || Number(geometry.y) + Number(geometry.height) > 1.001) return false;
    if (shapeType === 'freehand') return Array.isArray(geometry.points) && geometry.points.length >= 3 && geometry.points.length <= 300 && geometry.points.every(point => validNumber(point.x) && validNumber(point.y));
    return true;
  }
  function validDrawing(strokes) {
    if (!Array.isArray(strokes) || !strokes.length || strokes.length > 60) return false;
    let totalPoints = 0;
    const validNumber = value => Number.isFinite(Number(value));
    for (const stroke of strokes) {
      if (!stroke || !BRUSHES.has(stroke.brush) || stroke.tipMode !== undefined && !TIP_MODES.has(stroke.tipMode) || !/^#[0-9a-fA-F]{6}$/.test(String(stroke.color || ''))) return false;
      if (!validNumber(stroke.width) || Number(stroke.width) < 1 || Number(stroke.width) > 40) return false;
      if (!validNumber(stroke.opacity) || Number(stroke.opacity) < .1 || Number(stroke.opacity) > 1) return false;
      if (!Array.isArray(stroke.points) || stroke.points.length < 2 || stroke.points.length > 300) return false;
      totalPoints += stroke.points.length;
      if (totalPoints > 6000 || !stroke.points.every(point => validNumber(point.x) && validNumber(point.y) && Number(point.x) >= 0 && Number(point.x) <= 1 && Number(point.y) >= 0 && Number(point.y) <= 1 && (point.p === undefined || validNumber(point.p) && Number(point.p) >= 0 && Number(point.p) <= 1))) return false;
    }
    return true;
  }
  async function stopShare(roomId, actorId, reason = 'stopped') {
    const share = await activeShare(roomId);
    if (!share) return null;
    shareAnnotationPreviews.delete(roomId);
    if (shareDisconnectTimers.has(roomId)) {
      clearTimeout(shareDisconnectTimers.get(roomId));
      shareDisconnectTimers.delete(roomId);
    }
    const strokes = json(share.strokes, []);
    let drawing = null;
    const time = now();
    await db.exec('BEGIN IMMEDIATE');
    try {
      if (strokes.length && validDrawing(strokes)) {
        const drawingId = id('drawing');
        await db.prepare('INSERT INTO drawing_groups(id,room_id,session_id,author_id,strokes,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)').run(drawingId, roomId, share.session_id, share.presenter_id, JSON.stringify(strokes), 'visible', 1, time, time);
        drawing = await drawingView(await db.prepare('SELECT * FROM drawing_groups WHERE id=?').get(drawingId), {
          id: share.presenter_id
        });
      }
      await db.prepare("UPDATE room_view_shares SET status='ended',ended_at=?,updated_at=? WHERE room_id=?").run(time, time, roomId);
      await db.exec('COMMIT');
    } catch (error) {
      await db.exec('ROLLBACK');
      throw error;
    }
    await audit(roomId, actorId, 'share.ended', {
      targetUserId: share.presenter_id,
      details: {
        reason,
        strokes: strokes.length,
        drawingId: drawing?.id || null
      }
    });
    if (drawing) await broadcastContent(roomId, 'drawing.created', drawing, actorId);
    broadcast(roomId, 'share.ended', {
      presenterId: share.presenter_id,
      reason,
      drawingId: drawing?.id || null
    }, actorId);
    return {
      drawingId: drawing?.id || null,
      strokeCount: strokes.length
    };
  }
  async function clearSharedAnnotationSelection(roomId, annotationId, actorId) {
    const share = await activeShare(roomId);
    if (share?.selected_annotation_id !== annotationId) return;
    await db.prepare('UPDATE room_view_shares SET selected_annotation_id=NULL,updated_at=? WHERE room_id=?').run(now(), roomId);
    broadcast(roomId, 'share.annotation', {
      annotationId: null
    }, actorId);
  }
  async function startShare(room, presenterId, actorId) {
    if (await activeShare(room.id)) return null;
    const time = now();
    await db.prepare("INSERT INTO room_view_shares(room_id,presenter_id,session_id,status,viewport,selected_annotation_id,strokes,started_at,updated_at,ended_at) VALUES (?,?,?,'active','{}',NULL,'[]',?,?,NULL) ON CONFLICT(room_id) DO UPDATE SET presenter_id=excluded.presenter_id,session_id=excluded.session_id,status='active',viewport='{}',selected_annotation_id=NULL,strokes='[]',started_at=excluded.started_at,updated_at=excluded.updated_at,ended_at=NULL").run(room.id, presenterId, room.current_session_id, time, time);
    await db.prepare('DELETE FROM room_share_requests WHERE room_id=?').run(room.id);
    await audit(room.id, actorId, 'share.started', {
      targetUserId: presenterId
    });
    const view = shareView(await activeShare(room.id));
    broadcast(room.id, 'share.started', view, actorId);
    return view;
  }
  async function handle(req, res, url, parts, user) {
    if (parts[0] !== 'api' || parts[1] !== 'v2') return false;
    const method = req.method;
    if (method === 'GET' && parts[2] === 'rooms' && parts[3] === 'public') {
      const limit = Math.min(30, Math.max(1, Number(url.searchParams.get('limit') || 12)));
      const rows = await db.prepare("SELECT * FROM rooms WHERE visibility='public' AND status='active' ORDER BY last_activity_at DESC LIMIT ?").all(limit);
      return send(res, 200, {
        items: await Promise.all(rows.map(async room => await roomView(room, user?.id))),
        nextCursor: null,
        hasMore: false
      });
    }
    if (method === 'POST' && parts[2] === 'rooms' && parts.length === 3) {
      const me = await requireUser(user, res);
      if (!me) return true;
      const body = await readBody(req);
      const name = String(body.name || '').trim();
      const description = String(body.description || '').trim();
      const visibility = body.visibility === 'private' ? 'private' : 'public';
      const limit = Number(body.memberLimit || 100);
      const price = body.entryPrice === undefined ? 0 : Number(body.entryPrice);
      if (name.length < 2 || name.length > 50) return fail(res, 400, 'ROOM_NAME_INVALID', '房间名称需为 2-50 个字符'), true;
      if (description.length > 500) return fail(res, 400, 'ROOM_DESCRIPTION_TOO_LONG', '房间简介不能超过 500 字'), true;
      if (!Number.isInteger(limit) || limit < 2 || limit > 100) return fail(res, 400, 'ROOM_LIMIT_INVALID', '人数上限需为 2-100'), true;
      if (!Number.isInteger(price) || price < 0 || price > 5) return fail(res, 400, 'ROOM_PRICE_INVALID', '房间门票须为 0～5 枚墨币'), true;
      if (price > 0 && !me.expert_verified) return fail(res, 403, 'EXPERT_REQUIRED', '只有认证专家可以创建墨币房间'), true;
      const roomId = id('room');
      const time = now();
      const code = visibility === 'private' ? inviteCode() : null;
      await db.exec('BEGIN');
      try {
        await db.prepare('INSERT INTO rooms(id,owner_id,name,description,cover_url,visibility,member_limit,allow_member_invites,status,invite_code,created_at,updated_at,last_activity_at,entry_price) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)').run(roomId, me.id, name, description, String(body.coverUrl || '').trim() || null, visibility, limit, body.allowMemberInvites ? 1 : 0, 'active', code, time, time, time, price);
        await db.prepare('INSERT INTO room_members(room_id,user_id,role,status,joined_at,updated_at) VALUES (?,?,?,?,?,?)').run(roomId, me.id, 'owner', 'active', time, time);
        await db.exec('COMMIT');
      } catch (error) {
        await db.exec('ROLLBACK');
        throw error;
      }
      await audit(roomId, me.id, 'room.created', {
        details: {
          visibility,
          entryPrice: price
        }
      });
      return send(res, 201, await roomView(await roomById(roomId), me.id), '房间创建成功'), true;
    }
    if (method === 'POST' && parts[2] === 'artwork-assets' && parts.length === 3) {
      const me = await requireUser(user, res);
      if (!me) return true;
      const contentType = String(req.headers['content-type'] || '').split(';')[0].toLowerCase();
      const extensions = {
        'image/jpeg': 'jpg',
        'image/png': 'png',
        'image/webp': 'webp'
      };
      const extension = extensions[contentType];
      if (!extension) return fail(res, 415, 'ARTWORK_TYPE_UNSUPPORTED', '仅支持 JPG、PNG 和 WebP'), true;
      const max = 30 * 1024 * 1024;
      if (Number(req.headers['content-length'] || 0) > max) return fail(res, 413, 'ARTWORK_TOO_LARGE', '作品不能超过 30MB'), true;
      const assetId = id('asset');
      const original = path.join(uploadDir, `${assetId}-original.${extension}`);
      const normalized = path.join(uploadDir, `${assetId}.webp`);
      const preview = path.join(uploadDir, `${assetId}-preview.webp`);
      const thumb = path.join(uploadDir, `${assetId}-thumb.webp`);
      try {
        const size = await receiveUpload(req, original, max);
        const raw = fs.readFileSync(original);
        const hash = crypto.createHash('sha256').update(raw).digest('hex');
        const inputMetadata = await sharp(original).metadata();
        const orientedWidth = [5, 6, 7, 8].includes(inputMetadata.orientation) ? inputMetadata.height : inputMetadata.width;
        const orientedHeight = [5, 6, 7, 8].includes(inputMetadata.orientation) ? inputMetadata.width : inputMetadata.height;
        if (!orientedWidth || !orientedHeight || Math.max(orientedWidth, orientedHeight) > 20000) throw Object.assign(new Error('图片最长边不能超过 20000 像素'), {
          status: 400,
          code: 'ARTWORK_DIMENSIONS_INVALID'
        });
        await sharp(original).rotate().webp({
          quality: 92
        }).toFile(normalized);
        await sharp(original).rotate().resize({
          width: 2400,
          height: 2400,
          fit: 'inside',
          withoutEnlargement: true
        }).webp({
          quality: 88
        }).toFile(preview);
        await sharp(original).rotate().resize({
          width: 640,
          height: 640,
          fit: 'inside',
          withoutEnlargement: true
        }).webp({
          quality: 82
        }).toFile(thumb);
        const meta = await sharp(normalized).metadata();
        const filename = decodeURIComponent(String(req.headers['x-file-name'] || `作品.${extension}`)).slice(0, 180);
        await db.prepare('INSERT INTO artwork_assets(id,uploader_id,source_type,gallery_artwork_id,filename,original_url,url,preview_url,thumbnail_url,mime_type,size,width,height,sha256,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)').run(assetId, me.id, 'local', null, filename, `/uploads/${path.basename(original)}`, `/uploads/${path.basename(normalized)}`, `/uploads/${path.basename(preview)}`, `/uploads/${path.basename(thumb)}`, 'image/webp', size, meta.width, meta.height, hash, now());
        const asset = await db.prepare('SELECT * FROM artwork_assets WHERE id=?').get(assetId);
        return send(res, 201, {
          id: asset.id,
          sourceType: asset.source_type,
          galleryArtworkId: null,
          filename: asset.filename,
          originalUrl: asset.original_url,
          url: asset.url,
          previewUrl: asset.preview_url,
          thumbnailUrl: asset.thumbnail_url,
          mimeType: asset.mime_type,
          size: asset.size,
          width: asset.width,
          height: asset.height,
          sha256: asset.sha256
        }), true;
      } catch (error) {
        for (const file of [original, normalized, preview, thumb]) try {
          fs.rmSync(file, {
            force: true
          });
        } catch {}
        return fail(res, error.status || 400, error.code || 'ARTWORK_PROCESSING_FAILED', error.message || '作品处理失败'), true;
      }
    }
    if (method === 'GET' && parts[2] === 'artwork-assets' && parts[3]) {
      const me = await requireUser(user, res);
      if (!me) return true;
      const asset = await db.prepare('SELECT * FROM artwork_assets WHERE id=?').get(parts[3]);
      if (!asset) return fail(res, 404, 'ARTWORK_NOT_FOUND', '作品资源不存在'), true;
      const visibleSession = asset.uploader_id === me.id || await db.prepare("SELECT 1 FROM artwork_sessions s JOIN room_members m ON m.room_id=s.room_id WHERE s.asset_id=? AND m.user_id=? AND m.status='active' AND (m.role IN ('owner','member') OR s.status='active' OR s.visitor_visible=1) LIMIT 1").get(asset.id, me.id);
      if (!visibleSession) return fail(res, 404, 'ARTWORK_NOT_FOUND', '作品资源不存在'), true;
      return send(res, 200, {
        id: asset.id,
        sourceType: asset.source_type,
        galleryArtworkId: asset.gallery_artwork_id,
        filename: asset.filename,
        originalUrl: asset.original_url,
        url: asset.url,
        previewUrl: asset.preview_url,
        thumbnailUrl: asset.thumbnail_url,
        mimeType: asset.mime_type,
        size: asset.size,
        width: asset.width,
        height: asset.height,
        sha256: asset.sha256,
        createdAt: asset.created_at
      }), true;
    }
    if (parts[2] === 'room-invitations' && parts[3] && parts.length === 4 && method === 'GET') {
      const me = await requireUser(user, res);
      if (!me) return true;
      const invited = await db.prepare("SELECT * FROM rooms WHERE invite_code=? AND visibility='private'").get(parts[3]);
      if (!invited) return fail(res, 404, 'INVITATION_INVALID', '邀请码无效或已失效'), true;
      return send(res, 200, await roomView(invited, me.id), '获取成功'), true;
    }
    if (parts[2] === 'room-invitations' && parts[3] && parts[4] === 'join' && method === 'POST') {
      const me = await requireUser(user, res);
      if (!me) return true;
      const room = await db.prepare("SELECT * FROM rooms WHERE invite_code=? AND visibility='private'").get(parts[3]);
      if (!room) return fail(res, 404, 'INVITATION_INVALID', '邀请码无效或已失效'), true;
      if (room.status !== 'active') return fail(res, 409, 'ROOM_NOT_ACTIVE', '房间当前不可加入'), true;
      return await joinRoom(room, me, res, await readBody(req)), true;
    }
    if (parts[2] !== 'rooms' || !parts[3]) return fail(res, 404, 'API_NOT_FOUND', '接口不存在'), true;
    const roomId = parts[3];
    const room = await requireRoom(roomId, res);
    if (!room) return true;
    if (parts[4] === 'share') {
      const me = await requireUser(user, res);
      if (!me) return true;
      const membership = await requireMember(roomId, me, res);
      if (!membership) return true;
      if (method === 'GET' && parts.length === 5) return send(res, 200, {
        active: shareView(await activeShare(roomId)),
        requests: room.owner_id === me.id ? await shareRequests(roomId) : [],
        requested: Boolean(await db.prepare('SELECT 1 FROM room_share_requests WHERE room_id=? AND user_id=?').get(roomId, me.id))
      }), true;
      if (method === 'POST' && parts[5] === 'request') {
        if (room.status !== 'active' || !room.current_session_id) return fail(res, 409, 'SHARE_UNAVAILABLE', '当前没有可共享的作品'), true;
        if (membership.role !== 'member' || isMuted(membership)) return fail(res, 403, 'SHARE_REQUEST_DENIED', '只有未被禁言的普通成员可以申请共享'), true;
        if (await activeShare(roomId)) return fail(res, 409, 'SHARE_BUSY', '当前已有用户在共享'), true;
        await db.prepare('INSERT INTO room_share_requests(room_id,user_id,created_at) VALUES (?,?,?) ON CONFLICT(room_id,user_id) DO NOTHING').run(roomId, me.id, now());
        broadcast(roomId, 'share.requested', {
          userId: me.id,
          nickname: me.nickname
        }, me.id);
        return send(res, 200, {
          requested: true
        }, '申请已发送'), true;
      }
      if (method === 'DELETE' && parts[5] === 'request') {
        await db.prepare('DELETE FROM room_share_requests WHERE room_id=? AND user_id=?').run(roomId, me.id);
        broadcast(roomId, 'share.request.cancelled', {
          userId: me.id
        }, me.id);
        return send(res, 200, {
          requested: false
        }), true;
      }
      if (method === 'POST' && parts[5] === 'start') {
        if (room.owner_id !== me.id) return fail(res, 403, 'SHARE_OWNER_REQUIRED', '仅房主可以直接开始共享'), true;
        if (room.status !== 'active' || !room.current_session_id) return fail(res, 409, 'SHARE_UNAVAILABLE', '当前没有可共享的作品'), true;
        const view = await startShare(room, me.id, me.id);
        if (!view) return fail(res, 409, 'SHARE_BUSY', '当前已有用户在共享'), true;
        return send(res, 200, view, '共享已开始'), true;
      }
      if (method === 'POST' && ['approve', 'reject'].includes(parts[5])) {
        if (room.owner_id !== me.id) return fail(res, 403, 'SHARE_OWNER_REQUIRED', '仅房主可以处理共享申请'), true;
        const body = await readBody(req);
        const targetId = Number(body.userId);
        const request = await db.prepare('SELECT 1 FROM room_share_requests WHERE room_id=? AND user_id=?').get(roomId, targetId);
        if (!request) return fail(res, 404, 'SHARE_REQUEST_NOT_FOUND', '共享申请不存在'), true;
        if (parts[5] === 'reject') {
          await db.prepare('DELETE FROM room_share_requests WHERE room_id=? AND user_id=?').run(roomId, targetId);
          broadcast(roomId, 'share.request.rejected', {
            userId: targetId
          }, me.id);
          return send(res, 200, {
            rejected: true
          }), true;
        }
        const target = await activeMember(roomId, targetId);
        if (!target || target.role !== 'member' || isMuted(target)) return fail(res, 409, 'SHARE_PRESENTER_INVALID', '申请者已无共享权限'), true;
        if (!isOnline(roomId, targetId)) return fail(res, 409, 'SHARE_PRESENTER_OFFLINE', '申请者已离线，请等对方回到房间'), true;
        if (room.status !== 'active' || !room.current_session_id) return fail(res, 409, 'SHARE_UNAVAILABLE', '当前没有可共享的作品'), true;
        const view = await startShare(room, targetId, me.id);
        if (!view) return fail(res, 409, 'SHARE_BUSY', '当前已有用户在共享'), true;
        return send(res, 200, view, '已同意共享申请'), true;
      }
      if (method === 'POST' && parts[5] === 'stop') {
        const share = await activeShare(roomId);
        if (!share) return fail(res, 409, 'SHARE_NOT_ACTIVE', '当前无人共享'), true;
        if (share.presenter_id !== me.id && room.owner_id !== me.id) return fail(res, 403, 'SHARE_STOP_DENIED', '仅共享者或房主可以结束共享'), true;
        return send(res, 200, await stopShare(roomId, me.id), '共享已结束，已完成笔迹已保存'), true;
      }
      return fail(res, 404, 'API_NOT_FOUND', '接口不存在'), true;
    }
    if (method === 'GET' && parts[4] === 'artwork-assets' && parts[5] && parts[6] === 'file') {
      const mediaUser = await currentMediaUser(req);
      const mediaMember = mediaUser ? await activeMember(roomId, mediaUser.id) : null;
      if (!mediaMember) return fail(res, 403, 'ARTWORK_ACCESS_DENIED', '请先加入房间'), true;
      const visibility = mediaMember.role === 'visitor' ? " AND (s.status='active' OR s.visitor_visible=1)" : '';
      const asset = await db.prepare(`SELECT a.* FROM artwork_assets a JOIN artwork_sessions s ON s.asset_id=a.id WHERE s.room_id=? AND a.id=?${visibility} LIMIT 1`).get(roomId, parts[5]);
      if (!asset) return fail(res, 404, 'ARTWORK_NOT_FOUND', '作品不存在'), true;
      const column = {
        original: 'original_url',
        display: 'url',
        preview: 'preview_url',
        thumbnail: 'thumbnail_url'
      }[url.searchParams.get('variant') || 'preview'];
      if (!column) return fail(res, 400, 'ARTWORK_VARIANT_INVALID', '图片类型无效'), true;
      const filename = path.basename(asset[column] || '');
      const target = path.join(uploadDir, filename);
      if (!filename || !fs.existsSync(target)) return fail(res, 404, 'ARTWORK_FILE_NOT_FOUND', '图片文件不存在'), true;
      res.writeHead(200, {
        'Content-Type': column === 'original_url' ? {
          'jpg': 'image/jpeg',
          'png': 'image/png',
          'webp': 'image/webp'
        }[filename.split('.').pop()] || 'application/octet-stream' : 'image/webp',
        'Cache-Control': 'private, no-store',
        'X-Content-Type-Options': 'nosniff'
      });
      fs.createReadStream(target).pipe(res);
      return true;
    }
    if (method === 'GET' && parts.length === 4) return send(res, 200, await roomView(room, user?.id)), true;
    if (method === 'POST' && parts[4] === 'join') {
      const me = await requireUser(user, res);
      if (!me) return true;
      if (room.visibility !== 'public') return fail(res, 403, 'INVITATION_REQUIRED', '私有房间需要邀请码'), true;
      if (room.status !== 'active') return fail(res, 409, 'ROOM_NOT_ACTIVE', '房间当前不可加入'), true;
      return await joinRoom(room, me, res, await readBody(req)), true;
    }
    if (method === 'GET' && parts[4] === 'snapshot') {
      const me = await requireUser(user, res);
      if (!me) return true;
      const member = await requireMember(roomId, me, res);
      if (!member) return true;
      const members = await Promise.all((await db.prepare("SELECT * FROM room_members WHERE room_id=? AND status='active' ORDER BY CASE role WHEN 'owner' THEN 0 WHEN 'member' THEN 1 ELSE 2 END,joined_at").all(roomId)).map(memberView));
      let annotations = [];
      let drawings = [];
      if (room.current_session_id) {
        annotations = (await Promise.all((await db.prepare("SELECT * FROM annotations WHERE session_id=? AND status<>'deleted' ORDER BY created_at").all(room.current_session_id)).map(async row => await annotationView(row, me)))).filter(Boolean);
        drawings = (await Promise.all((await db.prepare("SELECT * FROM drawing_groups WHERE session_id=? AND status<>'deleted' ORDER BY created_at").all(room.current_session_id)).map(async row => await drawingView(row, me)))).filter(Boolean);
      }
      const messages = await Promise.all((await db.prepare('SELECT * FROM (SELECT * FROM room_messages WHERE room_id=? ORDER BY created_at DESC LIMIT 50) ORDER BY created_at').all(roomId)).map(messageView));
      const pendingTransfer = (await db.prepare("SELECT id,room_id AS roomId,from_user_id AS fromUserId,to_user_id AS toUserId,expires_at AS expiresAt,created_at AS createdAt FROM ownership_transfers WHERE room_id=? AND to_user_id=? AND status='pending' AND expires_at>? ORDER BY created_at DESC LIMIT 1").get(roomId, me.id, now())) || null;
      return send(res, 200, {
        room: await roomView(room, me.id),
        members,
        annotations,
        drawings,
        messages,
        pendingTransfer,
        share: shareView(await activeShare(roomId)),
        shareRequests: room.owner_id === me.id ? await shareRequests(roomId) : [],
        shareRequested: Boolean(await db.prepare('SELECT 1 FROM room_share_requests WHERE room_id=? AND user_id=?').get(roomId, me.id))
      }), true;
    }
    if (method === 'PATCH' && parts.length === 4) {
      const me = await requireUser(user, res);
      if (!me || !requireOwner(room, me, res)) return true;
      const body = await readBody(req);
      const name = body.name === undefined ? room.name : String(body.name).trim();
      const description = body.description === undefined ? room.description : String(body.description).trim();
      const visibility = body.visibility === undefined ? room.visibility : String(body.visibility);
      const limit = body.memberLimit === undefined ? room.member_limit : Number(body.memberLimit);
      if (name.length < 2 || name.length > 50) return fail(res, 400, 'ROOM_NAME_INVALID', '房间名称需为 2-50 个字符'), true;
      if (description.length > 500) return fail(res, 400, 'ROOM_DESCRIPTION_TOO_LONG', '房间简介不能超过 500 字'), true;
      if (!['public', 'private'].includes(visibility)) return fail(res, 400, 'ROOM_VISIBILITY_INVALID', '房间可见性无效'), true;
      if (!Number.isInteger(limit) || limit < 2 || limit > 100) return fail(res, 400, 'ROOM_LIMIT_INVALID', '人数上限需为 2-100'), true;
      let code = room.invite_code;
      const allow = body.allowMemberInvites === undefined ? room.allow_member_invites : body.allowMemberInvites ? 1 : 0;
      if (visibility === 'public' || room.allow_member_invites && !allow) code = null;
      if (visibility === 'private' && !code) code = inviteCode();
      await db.prepare('UPDATE rooms SET name=?,description=?,cover_url=?,visibility=?,member_limit=?,allow_member_invites=?,invite_code=?,updated_at=? WHERE id=?').run(name, description, body.coverUrl === undefined ? room.cover_url : String(body.coverUrl || '').trim() || null, visibility, limit, allow, code, now(), roomId);
      await audit(roomId, me.id, 'room.updated');
      const view = await roomView(await roomById(roomId), me.id);
      broadcast(roomId, 'room.updated', view, me.id);
      return send(res, 200, view, '房间资料已更新'), true;
    }
    if (method === 'POST' && ['end', 'reopen'].includes(parts[4])) {
      const me = await requireUser(user, res);
      if (!me || !requireOwner(room, me, res)) return true;
      const next = parts[4] === 'end' ? 'ended' : 'active';
      if (room.status === 'closed') return fail(res, 409, 'ROOM_CLOSED', '管理员关闭的房间不能重新开启'), true;
      if (next === 'ended' && (await activeShare(roomId))) await stopShare(roomId, me.id, 'room_ended');
      const pending = next === 'ended' ? await db.prepare("SELECT * FROM room_entry_payments WHERE room_id=? AND status='pending'").all(roomId) : [];
      for (const payment of pending) await ensureCoinWallet(payment.payer_id);
      await db.exec('BEGIN IMMEDIATE');
      try {
        for (const payment of pending) {
          await refundPayment(payment);
          await db.prepare("UPDATE room_members SET status='removed',removed_until=NULL,updated_at=? WHERE room_id=? AND user_id=? AND role<>'owner'").run(now(), roomId, payment.payer_id);
        }
        await db.prepare('UPDATE rooms SET status=?,updated_at=? WHERE id=?').run(next, now(), roomId);
        await db.exec('COMMIT');
      } catch (error) {
        await db.exec('ROLLBACK');
        throw error;
      }
      for (const payment of pending) disconnectUser(roomId, payment.payer_id);
      await audit(roomId, me.id, `room.${next}`, {
        details: {
          refunded: pending.length
        }
      });
      const view = await roomView(await roomById(roomId), me.id);
      broadcast(roomId, next === 'ended' ? 'room.ended' : 'room.reopened', view, me.id);
      if (next === 'ended') stopRoomVoice(roomId);
      return send(res, 200, view, next === 'ended' ? '房间已结束' : '房间已重新开启'), true;
    }
    const me = await requireUser(user, res);
    if (!me) return true;
    const member = await requireMember(roomId, me, res);
    if (!member) return true;
    if (method === 'GET' && parts[4] === 'members') {
      const members = await Promise.all((await db.prepare("SELECT * FROM room_members WHERE room_id=? AND status='active' ORDER BY CASE role WHEN 'owner' THEN 0 WHEN 'member' THEN 1 ELSE 2 END,joined_at").all(roomId)).map(memberView));
      return send(res, 200, {
        items: members,
        nextCursor: null,
        hasMore: false
      }), true;
    }
    if (parts[4] === 'members' && parts[5] && method === 'PATCH' && parts[6] === 'role') {
      if (!requireOwner(room, me, res)) return true;
      const targetId = Number(parts[5]);
      const target = await activeMember(roomId, targetId);
      const body = await readBody(req);
      const role = String(body.role);
      if (!target || target.role === 'owner' || !['visitor', 'member'].includes(role)) return fail(res, 400, 'MEMBER_ROLE_INVALID', '成员或目标角色无效'), true;
      if (role === 'visitor' && (await activeShare(roomId))?.presenter_id === targetId) await stopShare(roomId, me.id, 'role_changed');
      if (role === 'visitor') stopVoiceForUser(roomId, targetId);
      await db.prepare('UPDATE room_members SET role=?,updated_at=? WHERE room_id=? AND user_id=?').run(role, now(), roomId, targetId);
      await audit(roomId, me.id, 'member.role_changed', {
        targetUserId: targetId,
        details: {
          role
        }
      });
      const view = await memberView(await memberById(roomId, targetId));
      broadcast(roomId, 'room.member.role_changed', view, me.id);
      return send(res, 200, view, '成员权限已更新'), true;
    }
    if (parts[4] === 'members' && parts[5] && parts[6] === 'mute') {
      if (!requireOwner(room, me, res)) return true;
      const targetId = Number(parts[5]);
      const target = await activeMember(roomId, targetId);
      if (!target || target.role === 'owner') return fail(res, 400, 'MEMBER_INVALID', '该成员不可操作'), true;
      if (method === 'POST') {
        const body = await readBody(req);
        const minutes = Math.min(10080, Math.max(1, Number(body.minutes || 5)));
        const until = new Date(Date.now() + minutes * 60000).toISOString();
        if ((await activeShare(roomId))?.presenter_id === targetId) await stopShare(roomId, me.id, 'muted');
        await db.prepare('UPDATE room_members SET muted_until=?,updated_at=? WHERE room_id=? AND user_id=?').run(until, now(), roomId, targetId);
        stopVoiceForUser(roomId, targetId);
        await audit(roomId, me.id, 'member.muted', {
          targetUserId: targetId,
          details: {
            minutes,
            until
          }
        });
        const view = await memberView(await memberById(roomId, targetId));
        broadcast(roomId, 'room.member.muted', view, me.id);
        return send(res, 200, view, '成员已禁言'), true;
      }
      if (method === 'DELETE') {
        await db.prepare('UPDATE room_members SET muted_until=NULL,updated_at=? WHERE room_id=? AND user_id=?').run(now(), roomId, targetId);
        await audit(roomId, me.id, 'member.unmuted', {
          targetUserId: targetId
        });
        const view = await memberView(await memberById(roomId, targetId));
        broadcast(roomId, 'room.member.unmuted', view, me.id);
        return send(res, 200, view, '已解除禁言'), true;
      }
    }
    if (parts[4] === 'members' && parts[5] && parts[6] === 'voice-stop' && method === 'POST') {
      if (!requireOwner(room, me, res)) return true;
      if (!assertActive(room, res)) return true;
      const targetId = Number(parts[5]);
      const target = await activeMember(roomId, targetId);
      if (!target || target.role === 'owner') return fail(res, 400, 'MEMBER_INVALID', '该成员不可操作'), true;
      const stopped = stopVoiceForUser(roomId, targetId, 'owner_stopped');
      if (stopped) await audit(roomId, me.id, 'member.voice_stopped', {
        targetUserId: targetId
      });
      return send(res, 200, {
        userId: targetId,
        stopped
      }, stopped ? '已关闭该成员的麦克风' : '该成员当前未开麦'), true;
    }
    if (parts[4] === 'members' && parts[5] && parts.length === 6 && method === 'DELETE') {
      if (!requireOwner(room, me, res)) return true;
      const targetId = Number(parts[5]);
      const target = await activeMember(roomId, targetId);
      if (!target || target.role === 'owner') return fail(res, 400, 'MEMBER_INVALID', '该成员不可移除'), true;
      if ((await activeShare(roomId))?.presenter_id === targetId) await stopShare(roomId, me.id, 'member_removed');
      const until = new Date(Date.now() + 72 * 60 * 60 * 1000).toISOString();
      const payment = await db.prepare("SELECT * FROM room_entry_payments WHERE room_id=? AND payer_id=? AND status IN ('pending','settled')").get(roomId, targetId);
      if (payment) {
        await ensureCoinWallet(targetId);
        await ensureCoinWallet(payment.recipient_id);
      }
      await db.exec('BEGIN IMMEDIATE');
      try {
        if (payment) await refundPayment(payment);
        await db.prepare("UPDATE room_members SET status='removed',removed_until=?,updated_at=? WHERE room_id=? AND user_id=?").run(until, now(), roomId, targetId);
        await db.exec('COMMIT');
      } catch (error) {
        await db.exec('ROLLBACK');
        if (error.code === 'COIN_BALANCE_INSUFFICIENT') return fail(res, 409, 'HOST_REFUND_BALANCE_LOW', '房主余额不足以退还门票，无法移除付费成员，请联系管理员'), true;
        throw error;
      }
      await audit(roomId, me.id, 'member.removed', {
        targetUserId: targetId,
        details: {
          removedUntil: until,
          refunded: payment?.amount || 0
        }
      });
      broadcast(roomId, 'room.member.removed', {
        userId: targetId,
        removedUntil: until
      }, me.id);
      disconnectUser(roomId, targetId);
      return send(res, 200, {
        userId: targetId,
        removedUntil: until,
        refunded: payment?.amount || 0
      }, payment ? '成员已移除，门票已退还' : '成员已移除'), true;
    }
    if (parts[4] === 'members' && parts[5] && parts[6] === 'rejoin-block' && method === 'DELETE') {
      if (!requireOwner(room, me, res)) return true;
      const targetId = Number(parts[5]);
      const target = await memberById(roomId, targetId);
      if (!target) return fail(res, 404, 'MEMBER_NOT_FOUND', '成员记录不存在'), true;
      await db.prepare('UPDATE room_members SET removed_until=NULL,updated_at=? WHERE room_id=? AND user_id=?').run(now(), roomId, targetId);
      await audit(roomId, me.id, 'member.rejoin_block_cleared', {
        targetUserId: targetId
      });
      return send(res, 200, {
        userId: targetId
      }, '已解除重新加入限制'), true;
    }
    if (parts[4] === 'invitation') {
      const allowed = member.role === 'owner' || member.role === 'member' && room.allow_member_invites;
      if (!allowed) return fail(res, 403, 'INVITATION_ACCESS_DENIED', '无权查看或刷新邀请码'), true;
      if (method === 'GET') return send(res, 200, {
        code: room.invite_code,
        url: room.invite_code ? `/join-room?code=${room.invite_code}` : null
      }), true;
      if (method === 'POST' && parts[5] === 'refresh') {
        const code = inviteCode();
        await db.prepare("UPDATE rooms SET invite_code=?,visibility='private',updated_at=? WHERE id=?").run(code, now(), roomId);
        await audit(roomId, me.id, 'invitation.refreshed');
        return send(res, 200, {
          code,
          url: `/join-room?code=${code}`
        }, '邀请码已刷新'), true;
      }
    }
    if (parts[4] === 'ownership-transfers' && method === 'POST' && parts.length === 5) {
      if (!requireOwner(room, me, res)) return true;
      const body = await readBody(req);
      const targetId = Number(body.userId);
      const target = await activeMember(roomId, targetId);
      if (!target || target.role !== 'member') return fail(res, 400, 'TRANSFER_TARGET_INVALID', '只能向普通成员转让房主'), true;
      if (room.entry_price > 0 && !(await db.prepare('SELECT expert_verified FROM users WHERE id=?').get(targetId))?.expert_verified) return fail(res, 403, 'EXPERT_REQUIRED', '墨币房间只能转让给认证专家'), true;
      await db.prepare("UPDATE ownership_transfers SET status='cancelled',resolved_at=? WHERE room_id=? AND status='pending'").run(now(), roomId);
      const transferId = id('transfer');
      const expires = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
      await db.prepare("INSERT INTO ownership_transfers(id,room_id,from_user_id,to_user_id,status,expires_at,created_at) VALUES (?,?,?,?,?,?,?)").run(transferId, roomId, me.id, targetId, 'pending', expires, now());
      await audit(roomId, me.id, 'ownership.transfer_started', {
        targetUserId: targetId,
        targetType: 'ownership_transfer',
        targetId: transferId
      });
      broadcast(roomId, 'room.owner.transfer_requested', {
        id: transferId,
        toUserId: targetId,
        expiresAt: expires
      }, me.id);
      return send(res, 201, {
        id: transferId,
        toUserId: targetId,
        expiresAt: expires
      }, '转让请求已发送'), true;
    }
    if (parts[4] === 'ownership-transfers' && parts[5] && method === 'POST' && ['accept', 'reject'].includes(parts[6])) {
      const transfer = await db.prepare('SELECT * FROM ownership_transfers WHERE id=? AND room_id=?').get(parts[5], roomId);
      if (!transfer || transfer.status !== 'pending' || transfer.to_user_id !== me.id) return fail(res, 404, 'TRANSFER_NOT_FOUND', '转让请求不存在'), true;
      if (new Date(transfer.expires_at).getTime() <= Date.now()) {
        await db.prepare("UPDATE ownership_transfers SET status='expired',resolved_at=? WHERE id=?").run(now(), transfer.id);
        return fail(res, 409, 'TRANSFER_EXPIRED', '转让请求已过期'), true;
      }
      if (parts[6] === 'reject') {
        await db.prepare("UPDATE ownership_transfers SET status='rejected',resolved_at=? WHERE id=?").run(now(), transfer.id);
        await audit(roomId, me.id, 'ownership.transfer_rejected', {
          targetType: 'ownership_transfer',
          targetId: transfer.id
        });
        return send(res, 200, {
          id: transfer.id,
          status: 'rejected'
        }, '已拒绝转让'), true;
      }
      if (room.entry_price > 0 && !me.expert_verified) return fail(res, 403, 'EXPERT_REQUIRED', '专家认证已失效，不能接管墨币房间'), true;
      await db.exec('BEGIN');
      try {
        await db.prepare("UPDATE room_members SET role='member',updated_at=? WHERE room_id=? AND user_id=?").run(now(), roomId, transfer.from_user_id);
        await db.prepare("UPDATE room_members SET role='owner',updated_at=? WHERE room_id=? AND user_id=?").run(now(), roomId, me.id);
        await db.prepare('UPDATE rooms SET owner_id=?,updated_at=? WHERE id=?').run(me.id, now(), roomId);
        await db.prepare("UPDATE ownership_transfers SET status='accepted',resolved_at=? WHERE id=?").run(now(), transfer.id);
        await db.exec('COMMIT');
      } catch (error) {
        await db.exec('ROLLBACK');
        throw error;
      }
      await audit(roomId, me.id, 'ownership.transferred', {
        targetUserId: me.id,
        targetType: 'ownership_transfer',
        targetId: transfer.id
      });
      const view = await roomView(await roomById(roomId), me.id);
      broadcast(roomId, 'room.owner.transferred', view, me.id);
      return send(res, 200, view, '房主已转让'), true;
    }
    if (parts[4] === 'ownership-transfers' && parts[5] && method === 'DELETE') {
      if (!requireOwner(room, me, res)) return true;
      const transfer = await db.prepare("SELECT * FROM ownership_transfers WHERE id=? AND room_id=? AND status='pending'").get(parts[5], roomId);
      if (!transfer) return fail(res, 404, 'TRANSFER_NOT_FOUND', '待处理的转让请求不存在'), true;
      await db.prepare("UPDATE ownership_transfers SET status='cancelled',resolved_at=? WHERE id=?").run(now(), transfer.id);
      await audit(roomId, me.id, 'ownership.transfer_cancelled', {
        targetType: 'ownership_transfer',
        targetId: transfer.id
      });
      return send(res, 200, {
        id: transfer.id,
        status: 'cancelled'
      }, '转让请求已取消'), true;
    }
    if (parts[4] === 'artwork-sessions') {
      if (method === 'GET' && parts.length === 5) {
        const where = member.role === 'visitor' ? 's.room_id=? AND (s.status=\'active\' OR s.visitor_visible=1)' : 's.room_id=?';
        const rows = (await db.prepare(`SELECT s.*,a.url,a.preview_url,a.thumbnail_url,a.width,a.height,a.filename,a.source_type,a.gallery_artwork_id FROM artwork_sessions s JOIN artwork_assets a ON a.id=s.asset_id WHERE ${where} ORDER BY s.started_at DESC`).all(roomId)).map(row => sessionView(row, room));
        return send(res, 200, {
          items: rows,
          nextCursor: null,
          hasMore: false
        }), true;
      }
      if (method === 'POST' && parts.length === 5) {
        if (!requireOwner(room, me, res) || !assertActive(room, res)) return true;
        const body = await readBody(req);
        const asset = await db.prepare('SELECT * FROM artwork_assets WHERE id=?').get(String(body.assetId || ''));
        if (!asset) return fail(res, 404, 'ARTWORK_NOT_FOUND', '作品资源不存在'), true;
        const usedInRoom = await db.prepare('SELECT 1 FROM artwork_sessions WHERE room_id=? AND asset_id=? LIMIT 1').get(roomId, asset.id);
        if (asset.uploader_id !== me.id && !usedInRoom) return fail(res, 403, 'ARTWORK_ACCESS_DENIED', '只能使用自己上传或已在本房间展示的作品'), true;
        if (await activeShare(roomId)) return fail(res, 409, 'SHARE_ACTIVE', '请先结束共享视角，再更换作品'), true;
        if (room.entry_price > 0 && (await db.prepare('SELECT 1 FROM artwork_sessions s JOIN rooms r ON r.id=s.room_id WHERE s.asset_id=? AND r.entry_price=0 LIMIT 1').get(asset.id))) return fail(res, 409, 'ARTWORK_ALREADY_PUBLIC', '这幅作品已在免费房间公开，不能作为付费房间作品'), true;
        if (room.entry_price === 0 && (await db.prepare('SELECT 1 FROM artwork_sessions s JOIN rooms r ON r.id=s.room_id WHERE s.asset_id=? AND r.entry_price>0 LIMIT 1').get(asset.id))) return fail(res, 409, 'ARTWORK_PAID_ONLY', '这幅作品已用于墨币房间，不能直接放入免费房间'), true;
        const sessionId = id('session');
        const time = now();
        await db.exec('BEGIN');
        try {
          if (room.current_session_id) await db.prepare("UPDATE artwork_sessions SET status='ended',ended_at=? WHERE id=? AND status='active'").run(time, room.current_session_id);
          await db.prepare('INSERT INTO artwork_sessions(id,room_id,asset_id,title,visitor_visible,status,started_at,created_by) VALUES (?,?,?,?,?,?,?,?)').run(sessionId, roomId, asset.id, String(body.title || asset.filename).trim().slice(0, 100) || '未命名作品', body.visitorVisible ? 1 : 0, 'active', time, me.id);
          await db.prepare('UPDATE rooms SET current_session_id=?,cover_url=COALESCE(cover_url,?),updated_at=?,last_activity_at=? WHERE id=?').run(sessionId, room.entry_price > 0 ? null : asset.thumbnail_url, time, time, roomId);
          await db.exec('COMMIT');
        } catch (error) {
          await db.exec('ROLLBACK');
          throw error;
        }
        const row = await db.prepare('SELECT s.*,a.url,a.preview_url,a.thumbnail_url,a.width,a.height,a.filename,a.source_type,a.gallery_artwork_id FROM artwork_sessions s JOIN artwork_assets a ON a.id=s.asset_id WHERE s.id=?').get(sessionId);
        const view = sessionView(row, room);
        await audit(roomId, me.id, 'artwork.session_changed', {
          targetType: 'artwork_session',
          targetId: sessionId,
          details: {
            assetId: asset.id
          }
        });
        broadcast(roomId, 'artwork.session.changed', view, me.id);
        return send(res, 201, view, '作品已切换'), true;
      }
      if (method === 'GET' && parts[5] && parts.length === 6) {
        const row = await db.prepare('SELECT s.*,a.url,a.preview_url,a.thumbnail_url,a.width,a.height,a.filename,a.source_type,a.gallery_artwork_id FROM artwork_sessions s JOIN artwork_assets a ON a.id=s.asset_id WHERE s.id=? AND s.room_id=?').get(parts[5], roomId);
        if (!row) return fail(res, 404, 'SESSION_NOT_FOUND', '作品会话不存在'), true;
        if (member.role === 'visitor' && row.status !== 'active' && !row.visitor_visible) return fail(res, 403, 'HISTORY_ACCESS_DENIED', '无权查看该历史作品'), true;
        return send(res, 200, sessionView(row, room)), true;
      }
    }
    if (parts[4] === 'artwork-sessions' && parts[5] && parts[6] === 'visibility' && method === 'PATCH') {
      if (!requireOwner(room, me, res)) return true;
      const body = await readBody(req);
      const result = await db.prepare('UPDATE artwork_sessions SET visitor_visible=? WHERE id=? AND room_id=?').run(body.visitorVisible ? 1 : 0, parts[5], roomId);
      if (!result.changes) return fail(res, 404, 'SESSION_NOT_FOUND', '作品会话不存在'), true;
      return send(res, 200, {
        id: parts[5],
        visitorVisible: Boolean(body.visitorVisible)
      }, '历史作品权限已更新'), true;
    }
    if (parts[4] === 'artwork-sessions' && parts[5] && parts[6] === 'ai-critiques' && method === 'POST') {
      if (!assertActive(room, res)) return true;
      const critiqueShare = await activeShare(roomId);
      if (critiqueShare && critiqueShare.presenter_id !== me.id) return fail(res, 403, 'SHARE_PRESENTER_ONLY', '共享期间只有共享者可以创建点评草稿'), true;
      if (!canWrite(member)) return fail(res, 403, isMuted(member) ? 'ROOM_MEMBER_MUTED' : 'AI_CRITIQUE_WRITE_DENIED', isMuted(member) ? '当前处于禁言状态' : '仅普通成员和房主可使用智能点评', {
        mutedUntil: member.muted_until
      }), true;
      if (parts[5] !== room.current_session_id) return fail(res, 409, 'SESSION_NOT_CURRENT', '只能为当前作品生成智能点评'), true;
      if (typeof generateArtworkCritique !== 'function') return fail(res, 503, 'AI_CRITIQUE_UNAVAILABLE', '智能点评服务暂不可用'), true;
      const session = await db.prepare('SELECT s.*,a.url AS asset_url,a.preview_url AS asset_preview_url FROM artwork_sessions s JOIN artwork_assets a ON a.id=s.asset_id WHERE s.id=? AND s.room_id=?').get(parts[5], roomId);
      if (!session) return fail(res, 404, 'SESSION_NOT_FOUND', '作品会话不存在'), true;
      const body = await readBody(req);
      const shape = String(body.shapeType || '');
      if (!SHAPES.has(shape) || !validGeometry(shape, body.geometry)) return fail(res, 400, 'ANNOTATION_GEOMETRY_INVALID', '标注区域无效'), true;
      if (body.consent !== true) return fail(res, 400, 'AI_ARTWORK_CONSENT_REQUIRED', '请先同意将当前作品发送给 DeepSeek 进行分析'), true;
      const model = String(req.headers['x-deepseek-model'] || 'deepseek-flash').trim().slice(0, 100);
      const cacheRow = await db.prepare('SELECT context FROM artwork_ai_contexts WHERE session_id=? AND model=?').get(session.id, model);
      let generated;
      try {
        generated = await generateArtworkCritique(req, {
          asset: {
            url: session.asset_url,
            preview_url: session.asset_preview_url
          },
          session: {
            id: session.id,
            title: session.title
          },
          shapeType: shape,
          geometry: body.geometry,
          userQuestion: String(body.userQuestion || '').slice(0, 300),
          cachedContext: cacheRow ? json(cacheRow.context, null) : null
        });
      } catch (error) {
        console.error('智能批注生成失败：', String(error.message || error).replace(/sk-[A-Za-z0-9_-]+/g, '[REDACTED]'));
        return fail(res, error.status || 502, error.code || 'AI_CRITIQUE_FAILED', String(error.message || '智能点评生成失败').slice(0, 300)), true;
      }
      const time = now();
      await db.prepare("INSERT INTO artwork_ai_contexts(session_id,model,context,created_at,updated_at) VALUES (?,?,?,?,?) ON CONFLICT(session_id,model) DO UPDATE SET context=excluded.context,updated_at=excluded.updated_at").run(session.id, model, JSON.stringify(generated.overall), time, time);
      const draftId = id('ai_draft');
      await db.prepare('INSERT INTO ai_annotation_drafts(id,room_id,session_id,user_id,shape_type,geometry,content,overall_theme,selected_description,uncertainty,sources,provider,model,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)').run(draftId, roomId, session.id, me.id, shape, JSON.stringify(body.geometry), generated.content, generated.overall?.theme || '', generated.selectedRegionDescription || '', generated.uncertainty || '', JSON.stringify(generated.sources || []), generated.provider, generated.model, 'draft', time, time);
      return send(res, 201, {
        id: draftId,
        content: generated.content,
        overallTheme: generated.overall?.theme || '',
        selectedRegionDescription: generated.selectedRegionDescription || '',
        uncertainty: generated.uncertainty || '',
        sources: generated.sources || [],
        provider: generated.provider,
        model: generated.model,
        characterCount: Array.from(generated.content).length
      }, '智能点评草稿已生成'), true;
    }
    if (parts[4] === 'artwork-sessions' && parts[5] && parts[6] === 'annotations') {
      const session = await db.prepare('SELECT * FROM artwork_sessions WHERE id=? AND room_id=?').get(parts[5], roomId);
      if (!session) return fail(res, 404, 'SESSION_NOT_FOUND', '作品会话不存在'), true;
      if (session.status !== 'active' && member.role === 'visitor' && !session.visitor_visible) return fail(res, 403, 'HISTORY_ACCESS_DENIED', '无权查看该历史作品'), true;
      if (method === 'GET') {
        const rows = (await Promise.all((await db.prepare("SELECT * FROM annotations WHERE session_id=? AND status<>'deleted' ORDER BY created_at").all(session.id)).map(async row => await annotationView(row, me)))).filter(Boolean);
        return send(res, 200, {
          items: rows,
          nextCursor: null,
          hasMore: false
        }), true;
      }
      if (method === 'POST') {
        const share = await activeShare(roomId);
        if (share && share.presenter_id !== me.id) return fail(res, 403, 'SHARE_PRESENTER_ONLY', '共享期间只有共享者可以发布标注'), true;
        if (!assertActive(room, res)) return true;
        if (!canWrite(member)) return fail(res, 403, isMuted(member) ? 'ROOM_MEMBER_MUTED' : 'ANNOTATION_WRITE_DENIED', isMuted(member) ? '当前处于禁言状态' : '无权创建标注', {
          mutedUntil: member.muted_until
        }), true;
        if (session.id !== room.current_session_id) return fail(res, 409, 'SESSION_NOT_CURRENT', '只能在当前作品上创建标注'), true;
        const body = await readBody(req);
        let shape = String(body.shapeType || '');
        let geometry = body.geometry;
        let content = String(body.content || '').trim();
        let sourceType = 'human';
        let aiMetadata = null;
        let draft = null;
        if (body.aiDraftId) {
          draft = await db.prepare("SELECT * FROM ai_annotation_drafts WHERE id=? AND user_id=? AND room_id=? AND session_id=? AND status='draft'").get(String(body.aiDraftId), me.id, roomId, session.id);
          if (!draft) return fail(res, 409, 'AI_DRAFT_INVALID', '智能点评草稿不存在、已发布或不属于当前用户'), true;
          shape = draft.shape_type;
          geometry = json(draft.geometry);
          sourceType = 'ai_assisted';
          aiMetadata = JSON.stringify({
            draftId: draft.id,
            provider: draft.provider,
            model: draft.model,
            overallTheme: draft.overall_theme,
            selectedRegionDescription: draft.selected_description,
            uncertainty: draft.uncertainty,
            sources: json(draft.sources, [])
          });
        }
        if (!SHAPES.has(shape) || !validGeometry(shape, geometry)) return fail(res, 400, 'ANNOTATION_GEOMETRY_INVALID', '标注区域无效'), true;
        const count = Array.from(content).length;
        if (!content || count > 500 || sourceType === 'ai_assisted' && count < 100) return fail(res, 400, 'ANNOTATION_CONTENT_INVALID', sourceType === 'ai_assisted' ? '智能点评需为 100-500 字' : '点评需为 1-500 字'), true;
        const annotationId = id('ann');
        const time = now();
        await db.exec('BEGIN');
        try {
          await db.prepare('INSERT INTO annotations(id,room_id,session_id,author_id,shape_type,geometry,content,status,source_type,ai_metadata,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)').run(annotationId, roomId, session.id, me.id, shape, JSON.stringify(geometry), content, 'visible', sourceType, aiMetadata, 1, time, time);
          if (draft) await db.prepare("UPDATE ai_annotation_drafts SET status='published',content=?,updated_at=? WHERE id=?").run(content, time, draft.id);
          await db.exec('COMMIT');
        } catch (error) {
          await db.exec('ROLLBACK');
          throw error;
        }
        await touchRoom(roomId);
        const view = await annotationView(await db.prepare('SELECT * FROM annotations WHERE id=?').get(annotationId), me);
        await broadcastContent(roomId, 'annotation.created', view, me.id);
        return send(res, 201, view, sourceType === 'ai_assisted' ? 'AI辅助点评已发布' : '点评已发布'), true;
      }
    }
    if (parts[4] === 'artwork-sessions' && parts[5] && parts[6] === 'drawings') {
      const session = await db.prepare('SELECT * FROM artwork_sessions WHERE id=? AND room_id=?').get(parts[5], roomId);
      if (!session) return fail(res, 404, 'SESSION_NOT_FOUND', '作品会话不存在'), true;
      if (session.status !== 'active' && member.role === 'visitor' && !session.visitor_visible) return fail(res, 403, 'HISTORY_ACCESS_DENIED', '无权查看该历史作品'), true;
      if (method === 'GET') {
        const rows = (await Promise.all((await db.prepare("SELECT * FROM drawing_groups WHERE session_id=? AND status<>'deleted' ORDER BY created_at").all(session.id)).map(async row => await drawingView(row, me)))).filter(Boolean);
        return send(res, 200, {
          items: rows,
          nextCursor: null,
          hasMore: false
        }), true;
      }
      if (method === 'POST') {
        if (await activeShare(roomId)) return fail(res, 409, 'SHARE_ACTIVE', '共享期间请使用实时绘制'), true;
        if (!assertActive(room, res)) return true;
        if (!canWrite(member)) return fail(res, 403, isMuted(member) ? 'ROOM_MEMBER_MUTED' : 'DRAWING_WRITE_DENIED', isMuted(member) ? '当前处于禁言状态' : '无权发布绘制', {
          mutedUntil: member.muted_until
        }), true;
        if (session.id !== room.current_session_id) return fail(res, 409, 'SESSION_NOT_CURRENT', '只能在当前作品上进行绘制'), true;
        const body = await readBody(req);
        if (!validDrawing(body.strokes)) return fail(res, 400, 'DRAWING_STROKES_INVALID', '绘制内容无效或超出限制'), true;
        const drawingId = id('drawing');
        const time = now();
        await db.prepare('INSERT INTO drawing_groups(id,room_id,session_id,author_id,strokes,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)').run(drawingId, roomId, session.id, me.id, JSON.stringify(body.strokes), 'visible', 1, time, time);
        await touchRoom(roomId);
        const view = await drawingView(await db.prepare('SELECT * FROM drawing_groups WHERE id=?').get(drawingId), me);
        await broadcastContent(roomId, 'drawing.created', view, me.id);
        return send(res, 201, view, '绘制已发布'), true;
      }
    }
    if (parts[4] === 'messages') {
      if (method === 'GET') {
        const before = url.searchParams.get('before');
        const limit = Math.min(100, Math.max(1, Number(url.searchParams.get('limit') || 50)));
        const rows = before ? await db.prepare('SELECT * FROM room_messages WHERE room_id=? AND created_at<? ORDER BY created_at DESC LIMIT ?').all(roomId, before, limit) : await db.prepare('SELECT * FROM room_messages WHERE room_id=? ORDER BY created_at DESC LIMIT ?').all(roomId, limit);
        const ordered = await Promise.all(rows.reverse().map(messageView));
        return send(res, 200, {
          items: ordered,
          nextCursor: ordered[0]?.createdAt || null,
          hasMore: rows.length === limit
        }), true;
      }
      if (method === 'POST' && parts.length === 5) {
        if (!assertActive(room, res)) return true;
        if (!canWrite(member)) return fail(res, 403, isMuted(member) ? 'ROOM_MEMBER_MUTED' : 'CHAT_WRITE_DENIED', isMuted(member) ? '当前处于禁言状态' : '无权发送消息', {
          mutedUntil: member.muted_until
        }), true;
        const body = await readBody(req);
        const content = String(body.content || '').trim();
        if (!content || content.length > 600) return fail(res, 400, 'CHAT_CONTENT_INVALID', '聊天消息需为 1-600 字'), true;
        const messageId = id('msg');
        await db.prepare('INSERT INTO room_messages(id,room_id,author_id,content,status,created_at) VALUES (?,?,?,?,?,?)').run(messageId, roomId, me.id, content, 'visible', now());
        await touchRoom(roomId);
        const view = await messageView(await db.prepare('SELECT * FROM room_messages WHERE id=?').get(messageId));
        broadcast(roomId, 'chat.message.created', view, me.id);
        return send(res, 201, view), true;
      }
      if (method === 'DELETE' && parts[5]) {
        const message = await db.prepare('SELECT * FROM room_messages WHERE id=? AND room_id=?').get(parts[5], roomId);
        if (!message) return fail(res, 404, 'MESSAGE_NOT_FOUND', '消息不存在'), true;
        if (message.author_id !== me.id && room.owner_id !== me.id && me.role !== 'admin') return fail(res, 403, 'MESSAGE_DELETE_DENIED', '无权删除该消息'), true;
        await db.prepare("UPDATE room_messages SET status='deleted',deleted_by=?,deleted_at=? WHERE id=?").run(me.id, now(), message.id);
        await audit(roomId, me.id, 'chat.message_deleted', {
          targetType: 'chat_message',
          targetId: message.id
        });
        const view = await messageView(await db.prepare('SELECT * FROM room_messages WHERE id=?').get(message.id));
        broadcast(roomId, 'chat.message.deleted', view, me.id);
        return send(res, 200, view, '消息已删除'), true;
      }
    }
    return fail(res, 404, 'API_NOT_FOUND', '接口不存在'), true;
  }
  async function joinRoom(room, user, res, options = {}) {
    const existing = await memberById(room.id, user.id);
    if (existing?.status === 'active') return send(res, 200, await roomView(room, user.id), '已在房间中');
    if (existing?.removed_until && new Date(existing.removed_until).getTime() > Date.now()) return fail(res, 403, 'ROOM_REJOIN_BLOCKED', '暂时不能重新加入该房间', {
      removedUntil: existing.removed_until
    });
    const count = (await db.prepare("SELECT COUNT(*) AS n FROM room_members WHERE room_id=? AND status='active'").get(room.id)).n;
    if (count >= room.member_limit) return fail(res, 409, 'ROOM_FULL', '房间人数已满');
    const paid = room.entry_price > 0;
    const pass = paid ? await db.prepare("SELECT * FROM room_entry_payments WHERE room_id=? AND payer_id=? AND status IN ('pending','settled')").get(room.id, user.id) : null;
    if (paid && !pass) {
      if (options.acceptPrice !== true || Number(options.expectedPrice) !== room.entry_price) return fail(res, 409, 'ROOM_PRICE_CONFIRMATION_REQUIRED', '请先确认当前房间门票价格', {
        entryPrice: room.entry_price
      }), true;
      const owner = await db.prepare('SELECT expert_verified,status FROM users WHERE id=?').get(room.owner_id);
      if (!owner?.expert_verified || owner.status !== 0) return fail(res, 409, 'EXPERT_CERTIFICATION_PAUSED', '房主专家认证已失效，暂停新付费加入'), true;
      await ensureCoinWallet(user.id);
      if ((await roomSpendableCoins(user.id)) < room.entry_price) return fail(res, 409, 'EARNED_COINS_INSUFFICIENT', '可用于房间门票的任务墨币不足；初始赠币不可支付房费', {
        required: room.entry_price,
        available: await roomSpendableCoins(user.id)
      }), true;
    }
    const time = now();
    let paymentId = null;
    await db.exec('BEGIN IMMEDIATE');
    try {
      if (paid && !pass) {
        paymentId = id('payment');
        await applyCoinChange(user.id, -room.entry_price, {
          entryType: 'spend',
          sourceType: 'room_entry',
          sourceId: paymentId,
          description: `加入墨币房间：${room.name}`,
          idempotencyKey: `room:entry:${paymentId}`
        });
        await db.prepare("INSERT INTO room_entry_payments(id,room_id,payer_id,recipient_id,amount,status,created_at,settle_after) VALUES (?,?,?,?,?,'pending',?,?)").run(paymentId, room.id, user.id, room.owner_id, room.entry_price, time, new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString());
      }
      await db.prepare("INSERT INTO room_members(room_id,user_id,role,status,muted_until,removed_until,joined_at,updated_at) VALUES (?,?,?,'active',NULL,NULL,?,?) ON CONFLICT(room_id,user_id) DO UPDATE SET role='visitor',status='active',muted_until=NULL,removed_until=NULL,joined_at=excluded.joined_at,updated_at=excluded.updated_at").run(room.id, user.id, 'visitor', time, time);
      await db.exec('COMMIT');
    } catch (error) {
      await db.exec('ROLLBACK');
      if (error.code === 'COIN_BALANCE_INSUFFICIENT') return fail(res, 409, 'EARNED_COINS_INSUFFICIENT', error.message), true;
      throw error;
    }
    await audit(room.id, user.id, 'member.joined', {
      details: {
        entryPrice: paymentId ? room.entry_price : 0,
        paymentId
      }
    });
    const view = await roomView(room, user.id);
    broadcast(room.id, 'room.member.joined', await memberView(await memberById(room.id, user.id)), user.id);
    return send(res, 200, view, paymentId ? `已支付 ${room.entry_price} 枚墨币并加入房间` : '已加入房间');
  }
  async function handleAnnotation(req, res, parts, user) {
    const annotationId = parts[3];
    const row = await db.prepare('SELECT * FROM annotations WHERE id=?').get(annotationId);
    if (!row) return fail(res, 404, 'ANNOTATION_NOT_FOUND', '标注不存在'), true;
    const room = await roomById(row.room_id);
    const member = await requireMember(row.room_id, user, res);
    if (!member) return true;
    const mayManage = row.author_id === user.id || room.owner_id === user.id || user.role === 'admin';
    if (!mayManage) return fail(res, 403, 'ANNOTATION_MANAGE_DENIED', '无权管理该标注'), true;
    if (req.method === 'PATCH' && parts.length === 4) {
      if (!assertActive(room, res)) return true;
      if (isMuted(member)) return fail(res, 403, 'ROOM_MEMBER_MUTED', '当前处于禁言状态', {
        mutedUntil: member.muted_until
      }), true;
      const body = await readBody(req);
      if (Number(body.version) !== row.version) return fail(res, 409, 'ANNOTATION_VERSION_CONFLICT', '标注已被更新，请刷新后重试', {
        currentVersion: row.version
      }), true;
      const content = String(body.content ?? row.content).trim();
      const shape = String(body.shapeType ?? row.shape_type);
      const geometry = body.geometry ?? json(row.geometry);
      if (!content || content.length > 500) return fail(res, 400, 'ANNOTATION_CONTENT_INVALID', '点评需为 1-500 字'), true;
      if (!SHAPES.has(shape) || !validGeometry(shape, geometry)) return fail(res, 400, 'ANNOTATION_GEOMETRY_INVALID', '标注区域无效'), true;
      await db.prepare('UPDATE annotations SET shape_type=?,geometry=?,content=?,version=version+1,updated_at=? WHERE id=?').run(shape, JSON.stringify(geometry), content, now(), annotationId);
      const view = await annotationView(await db.prepare('SELECT * FROM annotations WHERE id=?').get(annotationId), user);
      await broadcastContent(room.id, 'annotation.updated', view, user.id);
      return send(res, 200, view, '点评已更新'), true;
    }
    if (req.method === 'POST' && ['hide', 'restore'].includes(parts[4])) {
      const hidden = parts[4] === 'hide';
      await db.prepare('UPDATE annotations SET status=?,hidden_by=?,hidden_at=?,version=version+1,updated_at=? WHERE id=?').run(hidden ? 'hidden' : 'visible', hidden ? user.id : null, hidden ? now() : null, now(), annotationId);
      const view = await annotationView(await db.prepare('SELECT * FROM annotations WHERE id=?').get(annotationId), user);
      await audit(room.id, user.id, hidden ? 'annotation.hidden' : 'annotation.restored', {
        targetType: 'annotation',
        targetId: annotationId
      });
      await broadcastContent(room.id, hidden ? 'annotation.hidden' : 'annotation.restored', view, user.id);
      if (hidden) await clearSharedAnnotationSelection(room.id, annotationId, user.id);
      return send(res, 200, view, hidden ? '标注已隐藏' : '标注已恢复'), true;
    }
    if (req.method === 'DELETE') {
      const body = await readBody(req);
      await db.prepare("UPDATE annotations SET status='deleted',deleted_by=?,deleted_at=?,delete_reason=?,version=version+1,updated_at=? WHERE id=?").run(user.id, now(), String(body.reason || '').slice(0, 200) || null, now(), annotationId);
      await audit(room.id, user.id, 'annotation.deleted', {
        targetType: 'annotation',
        targetId: annotationId
      });
      broadcast(room.id, 'annotation.deleted', {
        id: annotationId
      }, user.id);
      await clearSharedAnnotationSelection(room.id, annotationId, user.id);
      return send(res, 200, {
        id: annotationId
      }, '标注已删除'), true;
    }
    return fail(res, 404, 'API_NOT_FOUND', '接口不存在'), true;
  }
  async function handleDrawing(req, res, parts, user) {
    const drawingId = parts[3];
    const row = await db.prepare('SELECT * FROM drawing_groups WHERE id=?').get(drawingId);
    if (!row) return fail(res, 404, 'DRAWING_NOT_FOUND', '绘制不存在'), true;
    const room = await roomById(row.room_id);
    const member = await requireMember(row.room_id, user, res);
    if (!member) return true;
    const mayManage = row.author_id === user.id || room.owner_id === user.id || user.role === 'admin';
    if (!mayManage) return fail(res, 403, 'DRAWING_MANAGE_DENIED', '无权管理该绘制'), true;
    if (req.method === 'POST' && ['hide', 'restore'].includes(parts[4])) {
      const hidden = parts[4] === 'hide';
      await db.prepare('UPDATE drawing_groups SET status=?,hidden_by=?,hidden_at=?,version=version+1,updated_at=? WHERE id=?').run(hidden ? 'hidden' : 'visible', hidden ? user.id : null, hidden ? now() : null, now(), drawingId);
      const view = await drawingView(await db.prepare('SELECT * FROM drawing_groups WHERE id=?').get(drawingId), user);
      await audit(room.id, user.id, hidden ? 'drawing.hidden' : 'drawing.restored', {
        targetType: 'drawing',
        targetId: drawingId
      });
      await broadcastContent(room.id, hidden ? 'drawing.hidden' : 'drawing.restored', view, user.id);
      return send(res, 200, view, hidden ? '绘制已隐藏' : '绘制已恢复'), true;
    }
    if (req.method === 'DELETE') {
      const body = await readBody(req);
      await db.prepare("UPDATE drawing_groups SET status='deleted',deleted_by=?,deleted_at=?,delete_reason=?,version=version+1,updated_at=? WHERE id=?").run(user.id, now(), String(body.reason || '').slice(0, 200) || null, now(), drawingId);
      await audit(room.id, user.id, 'drawing.deleted', {
        targetType: 'drawing',
        targetId: drawingId
      });
      broadcast(room.id, 'drawing.deleted', {
        id: drawingId
      }, user.id);
      return send(res, 200, {
        id: drawingId
      }, '绘制已删除'), true;
    }
    return fail(res, 404, 'API_NOT_FOUND', '接口不存在'), true;
  }
  function attachWebSocket(server) {
    const wss = new WebSocketServer({
      server,
      path: '/ws',
      maxPayload: 100000
    });
    wss.on('connection', (ws, req) => {
      connections.add(ws);
      ws.once('close', () => connections.delete(ws));
      ws.pause();
      return socketWork(async () => {
        const url = new URL(req.url, 'http://localhost');
        const token = await consumeTicket(url.searchParams.get('ticket') || '', req.headers.origin);
        const user = await currentUser({
          headers: {
            authorization: `Bearer ${token}`
          }
        });
        if (!user) {
          ws.close(4401, '登录状态无效');
          return;
        }
        ws.userId = user.id;
        ws.authToken = token;
        ws.roomId = null;
        ws.on('message', raw => {
          return socketWork(async () => {
            let current;
            try{current=await currentUser({headers:{authorization:`Bearer ${token}`}});}catch{ws.close(1013,'平台身份暂不可确认');return;}
            if(!current){ws.close(4401,'登录状态已失效');return;}
            if (raw.length > 100000) return;
            let message;
            try {
              message = JSON.parse(raw.toString());
            } catch {
              return;
            }
            if (message.type === 'room.subscribe' && message.roomId) {
              const member = await activeMember(String(message.roomId), user.id);
              if (!member) {
                ws.send(JSON.stringify({
                  eventType: 'error',
                  data: {
                    code: 'ROOM_MEMBERSHIP_REQUIRED',
                    message: '请先加入房间'
                  }
                }));
                return;
              }
              if (ws.roomId) await unsubscribe(ws);
              ws.roomId = String(message.roomId);
              if (!socketsByRoom.has(ws.roomId)) socketsByRoom.set(ws.roomId, new Set());
              socketsByRoom.get(ws.roomId).add(ws);
              if ((await activeShare(ws.roomId))?.presenter_id === user.id && shareDisconnectTimers.has(ws.roomId)) {
                clearTimeout(shareDisconnectTimers.get(ws.roomId));
                shareDisconnectTimers.delete(ws.roomId);
              }
              ws.send(JSON.stringify({
                eventId: id('evt'),
                eventType: 'room.subscribed',
                roomId: ws.roomId,
                actorId: user.id,
                serverTime: now(),
                data: {
                  onlineCount: onlineCount(ws.roomId)
                }
              }));
              const preview = shareAnnotationPreviews.get(ws.roomId);
              if (preview) ws.send(JSON.stringify({
                eventId: id('evt'),
                eventType: 'share.annotation.preview',
                roomId: ws.roomId,
                actorId: (await activeShare(ws.roomId))?.presenter_id,
                serverTime: now(),
                data: {
                  preview
                }
              }));
              broadcastPresence(ws.roomId);
              return;
            }
            if (ws.roomId && String(message.type || '').startsWith('voice.')) {
              const roomId = ws.roomId,
                room = await roomById(roomId),
                member = await activeMember(roomId, user.id);
              if (!member || !room || room.status !== 'active') {
                voiceEvent(ws, 'voice.error', {
                  message: '房间已结束或成员身份失效'
                });
                return;
              }
              if (message.type === 'voice.join') {
                if (!ws.voicePeer) {
                  const peer = {
                    id: id('voice'),
                    ws,
                    speaking: false
                  };
                  ws.voicePeer = peer;
                  if (!voiceByRoom.has(roomId)) voiceByRoom.set(roomId, new Map());
                  voiceByRoom.get(roomId).set(peer.id, peer);
                  broadcast(roomId, 'voice.joined', {
                    peerId: peer.id,
                    userId: user.id,
                    speaking: false
                  }, user.id);
                }
                voiceEvent(ws, 'voice.roster', {
                  selfId: ws.voicePeer.id,
                  peers: voiceRoster(roomId),
                  limit: VOICE_SPEAKER_LIMIT
                });
                return;
              }
              if (message.type === 'voice.leave') {
                leaveVoice(ws);
                return;
              }
              if (!ws.voicePeer) return;
              if (message.type === 'voice.start') {
                if (!canWrite(member)) {
                  voiceEvent(ws, 'voice.error', {
                    message: '访客或被禁言成员不能开麦'
                  });
                  return;
                }
                if ([...voiceByRoom.get(roomId).values()].some(peer => peer.ws !== ws && peer.ws.userId === user.id && peer.speaking)) {
                  voiceEvent(ws, 'voice.error', {
                    message: '该账号已在另一个页面开麦'
                  });
                  return;
                }
                if (!ws.voicePeer.speaking && voiceRoster(roomId).filter(peer => peer.speaking).length >= VOICE_SPEAKER_LIMIT) {
                  voiceEvent(ws, 'voice.error', {
                    message: '当前已有 6 人开麦，请稍后再试'
                  });
                  return;
                }
                ws.voicePeer.speaking = true;
                broadcast(roomId, 'voice.updated', {
                  peerId: ws.voicePeer.id,
                  userId: user.id,
                  speaking: true
                }, user.id);
                return;
              }
              if (message.type === 'voice.stop') {
                ws.voicePeer.speaking = false;
                broadcast(roomId, 'voice.updated', {
                  peerId: ws.voicePeer.id,
                  userId: user.id,
                  speaking: false
                }, user.id);
                return;
              }
              if (message.type === 'voice.signal') {
                const target = voiceByRoom.get(roomId)?.get(String(message.to || '')),
                  signal = message.signal;
                if (!target || target.ws === ws || !signal || !['offer', 'answer', 'candidate'].includes(signal.type) || !ws.voicePeer.speaking && !target.speaking) return;
                if (signal.type === 'candidate' && (!signal.candidate || typeof signal.candidate !== 'object')) return;
                if ((signal.type === 'offer' || signal.type === 'answer') && (typeof signal.sdp !== 'string' || signal.sdp.length > 80000)) return;
                voiceEvent(target.ws, 'voice.signal', {
                  from: ws.voicePeer.id,
                  signal
                });
                return;
              }
              return;
            }
            if (!ws.roomId || !String(message.type || '').startsWith('share.')) return;
            const roomId = ws.roomId;
            const member = await activeMember(roomId, user.id);
            const share = await activeShare(roomId);
            const room = await roomById(roomId);
            if (!member || !share || !room || room.status !== 'active' || share.presenter_id !== user.id || isMuted(member)) return;
            if (message.type === 'share.viewport') {
              const viewport = message.viewport;
              if (!viewport || ![viewport.scale, viewport.panX, viewport.panY].every(Number.isFinite) || viewport.scale < .5 || viewport.scale > 5 || Math.abs(viewport.panX) > 2 || Math.abs(viewport.panY) > 2) return;
              await db.prepare('UPDATE room_view_shares SET viewport=?,updated_at=? WHERE room_id=?').run(JSON.stringify(viewport), now(), roomId);
              broadcast(roomId, 'share.viewport', viewport, user.id);
              return;
            }
            if (message.type === 'share.annotation') {
              const annotationId = message.annotationId === null ? null : String(message.annotationId || '');
              if (annotationId && !(await db.prepare("SELECT 1 FROM annotations WHERE id=? AND room_id=? AND session_id=? AND status='visible'").get(annotationId, roomId, share.session_id))) return;
              shareAnnotationPreviews.delete(roomId);
              await db.prepare('UPDATE room_view_shares SET selected_annotation_id=?,updated_at=? WHERE room_id=?').run(annotationId, now(), roomId);
              broadcast(roomId, 'share.annotation', {
                annotationId
              }, user.id);
              return;
            }
            if (message.type === 'share.annotation.preview') {
              const preview = message.preview;
              if (preview !== null && (!preview || !SHAPES.has(preview.shapeType) || !validGeometry(preview.shapeType, preview.geometry))) return;
              if (preview) shareAnnotationPreviews.set(roomId, preview);else shareAnnotationPreviews.delete(roomId);
              broadcast(roomId, 'share.annotation.preview', {
                preview
              }, user.id);
              return;
            }
            if (message.type === 'share.stroke.preview') {
              if (validDrawing([message.stroke])) broadcast(roomId, 'share.stroke.preview', {
                stroke: message.stroke
              }, user.id);
              return;
            }
            if (message.type === 'share.stroke.cancel') {
              broadcast(roomId, 'share.stroke.cancel', {}, user.id);
              return;
            }
            if (message.type === 'share.stroke.complete') {
              const strokes = json(share.strokes, []);
              const clientId = typeof message.stroke?.clientId === 'string' ? message.stroke.clientId : '';
              const existing = clientId && strokes.find(stroke => stroke.clientId === clientId);
              if (existing) {
                ws.send(JSON.stringify({
                  eventId: id('evt'),
                  eventType: 'share.stroke.complete',
                  roomId,
                  actorId: user.id,
                  serverTime: now(),
                  data: {
                    stroke: existing,
                    strokeCount: strokes.length
                  }
                }));
                return;
              }
              if (!validDrawing([...strokes, message.stroke])) {
                ws.send(JSON.stringify({
                  eventId: id('evt'),
                  eventType: 'share.stroke.rejected',
                  roomId,
                  actorId: user.id,
                  serverTime: now(),
                  data: {
                    clientId,
                    message: '笔画超出绘制限制，未能保存'
                  }
                }));
                return;
              }
              strokes.push(message.stroke);
              await db.prepare('UPDATE room_view_shares SET strokes=?,updated_at=? WHERE room_id=?').run(JSON.stringify(strokes), now(), roomId);
              broadcast(roomId, 'share.stroke.complete', {
                stroke: message.stroke,
                strokeCount: strokes.length
              }, user.id);
              return;
            }
            if (message.type === 'share.stroke.undo') {
              const strokes = json(share.strokes, []);
              if (!strokes.length) return;
              strokes.pop();
              await db.prepare('UPDATE room_view_shares SET strokes=?,updated_at=? WHERE room_id=?').run(JSON.stringify(strokes), now(), roomId);
              broadcast(roomId, 'share.stroke.undone', {
                strokeCount: strokes.length
              }, user.id);
              return;
            }
            if (message.type === 'share.stroke.clear') {
              const strokes = json(share.strokes, []);
              if (!strokes.length) return;
              await db.prepare('UPDATE room_view_shares SET strokes=?,updated_at=? WHERE room_id=?').run('[]', now(), roomId);
              broadcast(roomId, 'share.stroke.cleared', {
                strokeCount: 0
              }, user.id);
            }
          });
        });
        ws.on('close', () => socketWork(() => unsubscribe(ws)));
        ws.on('error', () => socketWork(() => unsubscribe(ws)));
        ws.resume();
      });
    });
    return wss;
  }
  async function revokeUser(userId, reason = 'account_disabled') {
    const targetId = Number(userId);
    let stoppedShares = 0, stoppedVoice = 0, closedConnections = 0;
    for (const share of await db.prepare("SELECT room_id FROM room_view_shares WHERE presenter_id=? AND status='active'").all(targetId)) {
      await stopShare(share.room_id, targetId, reason);
      stoppedShares++;
    }
    for (const roomId of voiceByRoom.keys()) stoppedVoice += stopVoiceForUser(roomId, targetId, reason);
    for (const ws of connections) {
      if (ws.userId !== targetId) continue;
      leaveVoice(ws);
      ws.close(4403, '账号访问权限已撤销');
      closedConnections++;
    }
    return { stoppedShares, stoppedVoice, closedConnections };
  }
  async function shutdown() {
    for(const timer of shareDisconnectTimers.values())clearTimeout(timer);
    shareDisconnectTimers.clear();
    for(const share of await db.prepare("SELECT room_id,presenter_id FROM room_view_shares WHERE status='active'").all())await stopShare(share.room_id,share.presenter_id,'server_restarted');
    for(const timer of shareDisconnectTimers.values())clearTimeout(timer);
    shareDisconnectTimers.clear();
  }
  async function unsubscribe(ws) {
    if (!ws.roomId) return;
    const roomId = ws.roomId;
    leaveVoice(ws);
    const set = socketsByRoom.get(roomId);
    set?.delete(ws);
    if (set && !set.size) socketsByRoom.delete(roomId);
    ws.roomId = null;
    broadcastPresence(roomId);
    if ((await activeShare(roomId))?.presenter_id === ws.userId && !isOnline(roomId, ws.userId) && !shareDisconnectTimers.has(roomId)) {
      const timer = setTimeout(() => {
        return socketWork(async () => {
          shareDisconnectTimers.delete(roomId);
          if ((await activeShare(roomId))?.presenter_id === ws.userId && !isOnline(roomId, ws.userId)) await stopShare(roomId, ws.userId, 'disconnected');
        });
      }, 10000);
      shareDisconnectTimers.set(roomId, timer);
    }
  }
  function disconnectUser(roomId, userId) {
    for (const ws of socketsByRoom.get(roomId) || []) if (ws.userId === userId) ws.close(4403, '已被移出房间');
  }
  async function route(req, res, url, parts, user) {
    if (parts[0] === 'api' && parts[1] === 'v2' && parts[2] === 'annotations' && parts[3]) {
      const me = await requireUser(user, res);
      if (!me) return true;
      return await handleAnnotation(req, res, parts, me);
    }
    if (parts[0] === 'api' && parts[1] === 'v2' && parts[2] === 'drawings' && parts[3]) {
      const me = await requireUser(user, res);
      if (!me) return true;
      return await handleDrawing(req, res, parts, me);
    }
    return await handle(req, res, url, parts, user);
  }
  for (const stale of await db.prepare("SELECT room_id,presenter_id FROM room_view_shares WHERE status='active'").all()) await stopShare(stale.room_id, stale.presenter_id, 'server_restarted');
  return {
    handle: route,
    revokeUser,
    shutdown,
    attachWebSocket,
    settleDuePayments,
    incomeSummary,
    adminPaymentList,
    adminRefundPayment,
    adminCloseRoom
  };
}
