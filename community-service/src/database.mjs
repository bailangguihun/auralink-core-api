import pg from 'pg';

export function postgresDatabase(connectionString) {
  if (!connectionString) throw new Error('COMMUNITY_DATABASE_URL is required');
  const pool = new pg.Pool({ connectionString, max: 6, connectionTimeoutMillis: 3000,
    idleTimeoutMillis: 10000, statement_timeout: 5000, application_name: 'artlive-community' });
  return {
    query: (text, values) => pool.query(text, values),
    async verifyRuntimeRole() {
      const result = await pool.query("SELECT rolsuper,rolcreatedb,rolcreaterole," +
        "has_schema_privilege(current_user,'community','CREATE') AS schema_create " +
        'FROM pg_roles WHERE rolname=current_user');
      const role = result.rows[0];
      if (!role || role.rolsuper || role.rolcreatedb || role.rolcreaterole || role.schema_create) {
        throw new Error('Community runtime requires a dedicated non-DDL role');
      }
      const outside = await pool.query("SELECT 1 FROM pg_tables WHERE schemaname NOT IN ('community','pg_catalog','information_schema') " +
        "AND (has_table_privilege(current_user,quote_ident(schemaname)||'.'||quote_ident(tablename),'INSERT') " +
        "OR has_table_privilege(current_user,quote_ident(schemaname)||'.'||quote_ident(tablename),'UPDATE') " +
        "OR has_table_privilege(current_user,quote_ident(schemaname)||'.'||quote_ident(tablename),'DELETE') " +
        "OR has_table_privilege(current_user,quote_ident(schemaname)||'.'||quote_ident(tablename),'TRUNCATE')) LIMIT 1");
      if (outside.rows.length) throw new Error('Community runtime must not write other schemas');
    },
    async transaction(work) {
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        const value = await work(client);
        await client.query('COMMIT');
        return value;
      } catch (error) {
        try { await client.query('ROLLBACK'); } catch { /* Preserve original error; no retry. */ }
        throw error;
      } finally { client.release(); }
    },
    close: () => pool.end(),
  };
}
