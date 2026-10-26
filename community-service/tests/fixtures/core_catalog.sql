-- Disposable community_core_fixture database only; not a production migration.
CREATE TABLE catalog_users (
 id bigint GENERATED ALWAYS AS IDENTITY (START WITH 101) PRIMARY KEY,
 username text UNIQUE NOT NULL, password_hash text NOT NULL, full_name text NOT NULL,
 email text UNIQUE NOT NULL, enabled boolean NOT NULL, account_non_expired boolean NOT NULL,
 account_non_locked boolean NOT NULL, credentials_non_expired boolean NOT NULL,
 role text NOT NULL, created_at timestamp NOT NULL, updated_at timestamp NOT NULL
);
-- A deliberately different JPA identity proves that catalog authentication is used.
CREATE TABLE users(id bigint PRIMARY KEY, username text);
INSERT INTO users VALUES (1,'community_fixture_a');
CREATE TABLE catalog_painting_favorites(user_id bigint, painting_id uuid, PRIMARY KEY(user_id,painting_id));
CREATE TABLE catalog_batch_current_revisions(batch_id uuid PRIMARY KEY, revision int);
CREATE TABLE catalog_source_records(batch_id uuid,revision int,annotation_file_id text,
 record_number int,painting_id uuid,source_record_key text,record_state text);
CREATE TABLE catalog_paintings(public_id uuid PRIMARY KEY,current_batch_id uuid,current_revision int,
 title text,author_name text,category text,catalog_status text,image_display_status text,image_asset_id uuid);
CREATE TABLE catalog_media_assets(public_id uuid PRIMARY KEY,source_batch_id uuid,source_revision int,
 relative_path text,storage_uri text,content_sha256 text,media_status text);
CREATE TABLE catalog_annotation_field_definitions(batch_id uuid,revision int,annotation_file_id text,
 field_key text,label text,value_type text,unit text,field_group text,display_order int,
 visibility text,filterability text,original_column_name text);
CREATE TABLE catalog_annotation_revisions(batch_id uuid,revision int,annotation_file_id text,record_number int,
 field_key text,raw_value text,normalized_value text,value_status text,assertion_status text);
