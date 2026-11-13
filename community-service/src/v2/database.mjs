import { AsyncLocalStorage } from 'node:async_hooks';
import { referenceTables } from './tables.mjs';

const tables = new Set(referenceTables);
const utc = `(to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'))`;
/** A finite SQL dialect adapter for the reviewed reference queries, not a second database. */
export function referenceSql(input) {
  let query = input.trim();
  const ignore = /^INSERT OR IGNORE\b/i.test(query);
  query = query.replace(/^INSERT OR IGNORE\b/i, 'INSERT');
  query = query.replace(/datetime\('now','-24 hours'\)/gi, "(clock_timestamp()-interval '24 hours')")
    .replace(/datetime\(([^()]+)\)/gi, 'CAST($1 AS timestamptz)')
    .replace(/\bMAX\(0,/g, 'GREATEST(0,')
    .replace(/\browid\b/g, 'ordinal')
    .replace('SET amount=amount+excluded.amount','SET amount=room_income_debts.amount+excluded.amount');
  // Lex strings before replacing identifiers/placeholders. Never interpolate values.
  let parameter = 0;
  query = query.replace(/'(?:''|[^'])*'|"(?:""|[^"])*"|\b[A-Za-z_][A-Za-z0-9_]*\b|\?/g, word => {
    if (word[0] === "'" || word[0] === '"') return word;
    if (word === '?') return '$' + (++parameter);
    if (word.toUpperCase() === 'LIKE') return 'ILIKE';
    if (word === 'CURRENT_TIMESTAMP') return utc;
    if (/[a-z][A-Z]/.test(word)) return '"'+word+'"';
    return tables.has(word) ? 'community.v2_' + word : word;
  });
  query = query.replace(/\bAS ([a-z]+[A-Z][a-zA-Z]*)\b/g, 'AS "$1"');
  if (ignore) query += ' ON CONFLICT DO NOTHING';
  return query;
}
const normalize = row => Object.fromEntries(Object.entries(row).map(([key,value]) => [key,
  typeof value === 'string' && /^-?\d+$/.test(value) && (/[a-z]Id$/.test(key)||['n','total','id','user_id','owner_id','author_id','uploader_id','payer_id','recipient_id','presenter_id','actor_id','from_user_id','to_user_id','target_user_id','hidden_by','deleted_by','created_by','reply_user_id','sum','spent','amount','balance','lifetime_earned','lifetime_spent'].includes(key)) && Number.isSafeInteger(Number(value)) ? Number(value) : value]));

export function referenceDatabase(database) {
  const scope = new AsyncLocalStorage();
  async function query(text, values=[]) {
    const context=scope.getStore();
    if(!context?.active)return database.query(text,values);
    const work=context.queue.catch(()=>{}).then(()=>context.client.query(text,values));
    context.queue=work;
    return work;
  }
  return {
    async unit(work,serialize=true) {
      if(scope.getStore()?.active) return work();
      let context;
      const result=await database.transaction(client => {
       context={client,active:true,queue:Promise.resolve(),effects:[]};
       return scope.run(context, async () => {
        await client.query("SET LOCAL artlive.reference_write='on'");
        // Preserve SQLite's single-writer ordering across admission, wallet and role checks.
        if(serialize)await client.query("SELECT pg_advisory_xact_lock(194717,3)");
        try{return await work();}finally{await scope.getStore().queue.catch(()=>{});scope.getStore().active=false;}
       });
      });
      for(const effect of context.effects)effect();
      return result;
    },
    afterCommit(work){const context=scope.getStore();if(context?.active)context.effects.push(work);else work();},
    query,
    prepare(sql) {
      const text=referenceSql(sql);
      return {
        async get(...values) { const result=await query(text,values); return result.rows[0] && normalize(result.rows[0]); },
        async all(...values) { return (await query(text,values)).rows.map(normalize); },
        async run(...values) {
          const insert=/^INSERT INTO community\.v2_(\w+)/i.exec(text);
          const hasId=insert && !['post_tags','post_likes','favorites','comment_likes','coin_wallets','ai_settings','room_members','artwork_ai_contexts','room_share_requests','room_view_shares','room_income_debts'].includes(insert[1]);
          const result=await query(text+(hasId?' RETURNING id':''),values);
          return {changes:result.rowCount,lastInsertRowid:result.rows[0]?.id};
        }
      };
    },
    async exec(sql) {
      // All command groups are enclosed by unit(). No application DDL, no nested commits.
      if(/^(BEGIN(?: IMMEDIATE)?|COMMIT|ROLLBACK)$/i.test(sql.trim())) {
        if(!scope.getStore()?.active)throw new Error('Reference transaction requires a managed unit');
        return;
      }
      throw new Error('Unexpected runtime SQL command');
    }
  };
}
