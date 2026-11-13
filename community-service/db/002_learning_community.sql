-- Additive community upgrade. Apply explicitly with the migration owner.
-- Existing identities, gallery references, reactions and operation receipts remain.
BEGIN;
ALTER TABLE community.posts
  ADD COLUMN title varchar(100) NOT NULL DEFAULT '',
  ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'discussion' CHECK(kind IN ('discussion','artwork')),
  ADD COLUMN creation_stage varchar(16) NOT NULL DEFAULT '',
  ADD COLUMN creation_goal varchar(1000) NOT NULL DEFAULT '',
  ADD COLUMN help_topics text[] NOT NULL DEFAULT '{}',
  ADD COLUMN tags text[] NOT NULL DEFAULT '{}',
  ADD COLUMN workflow_status varchar(24) NOT NULL DEFAULT 'sharing'
    CHECK(workflow_status IN ('sharing','seeking_feedback','revising','completed')),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX community_posts_kind_recent ON community.posts(kind,created_at DESC,id DESC) WHERE status='ACTIVE';
CREATE INDEX community_posts_tags ON community.posts USING gin(tags);
CREATE TABLE community.media_assets (
  id uuid PRIMARY KEY,
  owner_id bigint NOT NULL REFERENCES community.profiles(user_id),
  filename varchar(180) NOT NULL,
  original_key varchar(100) NOT NULL UNIQUE,
  preview_key varchar(100) NOT NULL UNIQUE,
  mime_type varchar(32) NOT NULL CHECK(mime_type IN ('image/jpeg','image/png','image/webp')),
  bytes integer NOT NULL CHECK(bytes BETWEEN 1 AND 10485760),
  width integer NOT NULL CHECK(width>0),
  height integer NOT NULL CHECK(height>0),
  sha256 char(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE community.artwork_revisions (
  id uuid PRIMARY KEY,
  post_id uuid NOT NULL REFERENCES community.posts(id),
  version_number integer NOT NULL CHECK(version_number>=2),
  note varchar(2000) NOT NULL,
  reflection varchar(2000) NOT NULL DEFAULT '',
  review_status varchar(16) NOT NULL DEFAULT 'not_requested' CHECK(review_status IN ('not_requested','requested','completed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(post_id,version_number)
);
CREATE TABLE community.post_images (
  post_id uuid NOT NULL REFERENCES community.posts(id),
  asset_id uuid NOT NULL REFERENCES community.media_assets(id),
  revision_id uuid REFERENCES community.artwork_revisions(id),
  position integer NOT NULL CHECK(position BETWEEN 0 AND 8)
);
CREATE UNIQUE INDEX community_post_base_images ON community.post_images(post_id,position) WHERE revision_id IS NULL;
CREATE UNIQUE INDEX community_revision_images ON community.post_images(revision_id,position) WHERE revision_id IS NOT NULL;
CREATE INDEX community_image_access ON community.post_images(asset_id,post_id);
ALTER TABLE community.comments
  ADD COLUMN parent_id uuid REFERENCES community.comments(id),
  ADD COLUMN dimension varchar(16),
  ADD COLUMN suggestion varchar(1000) NOT NULL DEFAULT '',
  ADD COLUMN adopted boolean NOT NULL DEFAULT false,
  ADD COLUMN revision_id uuid REFERENCES community.artwork_revisions(id);
CREATE TABLE community.artwork_tasks (
  id uuid PRIMARY KEY,
  post_id uuid NOT NULL REFERENCES community.posts(id),
  source_comment_id uuid NOT NULL REFERENCES community.comments(id),
  content varchar(1000) NOT NULL,
  dimension varchar(16) NOT NULL,
  status varchar(8) NOT NULL DEFAULT 'todo' CHECK(status IN ('todo','done')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(post_id,source_comment_id)
);
CREATE TABLE community.notifications (
  id uuid PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES community.profiles(user_id),
  actor_id bigint NOT NULL REFERENCES community.profiles(user_id),
  post_id uuid NOT NULL REFERENCES community.posts(id),
  kind varchar(24) NOT NULL CHECK(kind IN ('comment','reply','like','adopt','review_request','review_complete')),
  created_at timestamptz NOT NULL DEFAULT now(),
  read_at timestamptz
);
CREATE INDEX community_notifications_user ON community.notifications(user_id,created_at DESC,id DESC);
CREATE TABLE community.knowledge_cases (
  id uuid PRIMARY KEY,
  post_id uuid NOT NULL UNIQUE REFERENCES community.posts(id),
  title varchar(160) NOT NULL,
  summary varchar(3000) NOT NULL,
  lessons jsonb NOT NULL DEFAULT '[]',
  dimensions text[] NOT NULL DEFAULT '{}',
  status varchar(16) NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','published')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
INSERT INTO community.schema_version(version) VALUES(2);
COMMIT;
