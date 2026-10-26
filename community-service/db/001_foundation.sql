-- Explicit, operator-run migration. The application never executes DDL.
-- Run in an isolated database first. Do not apply using the Core runtime role.
BEGIN;
CREATE SCHEMA community;
CREATE TABLE community.schema_version (
  version integer PRIMARY KEY,
  installed_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO community.schema_version(version) VALUES (1);
CREATE TABLE community.profiles (
  user_id bigint PRIMARY KEY CHECK (user_id > 0),
  display_name varchar(160) NOT NULL
);
CREATE TABLE community.posts (
  id uuid PRIMARY KEY,
  author_id bigint NOT NULL REFERENCES community.profiles(user_id),
  content text NOT NULL CHECK (char_length(content) BETWEEN 1 AND 5000),
  gallery_artwork_id uuid,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DELETED')),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX community_posts_recent ON community.posts(created_at DESC,id DESC) WHERE status='ACTIVE';
CREATE TABLE community.comments (
  id uuid PRIMARY KEY,
  post_id uuid NOT NULL REFERENCES community.posts(id),
  author_id bigint NOT NULL REFERENCES community.profiles(user_id),
  content text NOT NULL CHECK (char_length(content) BETWEEN 1 AND 1000),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX community_comments_post ON community.comments(post_id,created_at,id);
CREATE TABLE community.reactions (
  post_id uuid NOT NULL REFERENCES community.posts(id),
  user_id bigint NOT NULL REFERENCES community.profiles(user_id),
  kind varchar(16) NOT NULL CHECK (kind IN ('like','favorite')),
  PRIMARY KEY (post_id,user_id,kind)
);
CREATE TABLE community.operations (
  user_id bigint NOT NULL,
  request_id uuid NOT NULL,
  fingerprint char(64) NOT NULL,
  result jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id,request_id)
);
CREATE INDEX community_operations_recent ON community.operations(user_id,created_at DESC);
-- No initial accounts, coins, demo posts, or cross-schema foreign keys.
COMMIT;
