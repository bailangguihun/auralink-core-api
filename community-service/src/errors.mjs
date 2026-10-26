export class Failure extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
export function invalid(message = '请求内容有误。') {
  return new Failure(400, 'COMMUNITY_INVALID_REQUEST', message);
}
export function uuid(value) {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)) throw invalid('标识格式有误。');
  return value;
}
export function strictObject(value, allowed) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).some(key => !allowed.includes(key))) throw invalid();
  return value;
}
export function content(value, max) {
  if (typeof value !== 'string') throw invalid();
  const text = value.trim();
  if (!text || [...text].length > max) throw invalid('内容长度超出限制或为空。');
  return text;
}
