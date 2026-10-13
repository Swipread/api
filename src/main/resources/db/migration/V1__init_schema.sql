CREATE EXTENSION IF NOT EXISTS vector;
CREATE SCHEMA app;
CREATE SCHEMA reco;

CREATE TABLE app.users (
  id            text PRIMARY KEY,              -- u_...
  email         text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  display_name  text NOT NULL,
  locale        text NOT NULL DEFAULT 'fr-FR',
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.topics (
  id    serial PRIMARY KEY,
  slug  text NOT NULL UNIQUE,
  label text NOT NULL
);

CREATE TABLE app.user_topics (
  user_id    text REFERENCES app.users(id) ON DELETE CASCADE,
  topic_id   int  REFERENCES app.topics(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, topic_id)
);

CREATE TABLE app.sources (
  id       serial PRIMARY KEY,
  name     text NOT NULL,
  site_url text NOT NULL,
  feed_url text NOT NULL UNIQUE,
  language text NOT NULL,
  license  text,                               -- conditions de réutilisation
  active   boolean NOT NULL DEFAULT true
);

CREATE TABLE app.articles (
  id           text PRIMARY KEY,               -- a_...
  source_id    int  NOT NULL REFERENCES app.sources(id),
  url          text NOT NULL UNIQUE,
  title        text NOT NULL,
  summary      text NOT NULL,
  image_url    text,
  author       text,
  language     text NOT NULL,
  word_count   int  NOT NULL,
  published_at timestamptz NOT NULL,
  ingested_at  timestamptz NOT NULL DEFAULT now(),
  embedding    vector(384)
);
CREATE INDEX articles_published_idx ON app.articles (published_at DESC);
CREATE INDEX articles_embedding_idx ON app.articles
  USING hnsw (embedding vector_cosine_ops);

CREATE TABLE app.article_topics (
  article_id text REFERENCES app.articles(id) ON DELETE CASCADE,
  topic_id   int  REFERENCES app.topics(id),
  confidence real NOT NULL,
  PRIMARY KEY (article_id, topic_id)
);

CREATE TABLE app.events (
  event_id    text NOT NULL,                   -- e_..., généré par le front
  user_id     text NOT NULL,
  session_id  text NOT NULL,
  type        text NOT NULL,
  article_id  text,
  request_id  text,
  position    int,
  payload     jsonb NOT NULL DEFAULT '{}',
  occurred_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX events_user_idx ON app.events (user_id, occurred_at DESC);
CREATE TABLE app.events_2026_10 PARTITION OF app.events
  FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE TABLE app.user_articles (
  user_id        text REFERENCES app.users(id) ON DELETE CASCADE,
  article_id     text REFERENCES app.articles(id) ON DELETE CASCADE,
  first_seen_at  timestamptz NOT NULL,
  total_dwell_ms int NOT NULL DEFAULT 0,
  max_scroll     real,
  liked_at       timestamptz,
  saved_at       timestamptz,
  hidden_at      timestamptz,
  PRIMARY KEY (user_id, article_id)
);
CREATE INDEX user_articles_saved_idx ON app.user_articles (user_id, saved_at DESC)
  WHERE saved_at IS NOT NULL;

CREATE TABLE reco.user_profiles (
  user_id      text PRIMARY KEY,
  embedding    vector(384) NOT NULL,
  events_count int NOT NULL DEFAULT 0,
  updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reco.model_runs (
  id          serial PRIMARY KEY,
  version     text NOT NULL,                   -- ex. content-v1
  started_at  timestamptz NOT NULL,
  finished_at timestamptz,
  metrics     jsonb
);
