INSERT INTO app.topics (slug, label) VALUES
  ('tech', 'Tech'),
  ('science', 'Sciences'),
  ('politics', 'Politique'),
  ('economy', 'Économie'),
  ('culture', 'Culture'),
  ('sport', 'Sport'),
  ('health', 'Santé'),
  ('environment', 'Environnement')
ON CONFLICT (slug) DO NOTHING;
