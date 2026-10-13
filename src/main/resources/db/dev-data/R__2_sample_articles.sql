INSERT INTO app.sources (name, site_url, feed_url, language, license)
VALUES ('Sample source', 'https://example.org', 'https://example.org/rss', 'fr', 'dev only')
ON CONFLICT (feed_url) DO NOTHING;

INSERT INTO app.articles (id, source_id, url, title, summary, language, word_count, published_at)
SELECT 'a_dev' || lpad(n::text, 4, '0'),
       (SELECT id FROM app.sources WHERE feed_url = 'https://example.org/rss'),
       'https://example.org/articles/' || n,
       'Sample article ' || n,
       'Summary of sample article ' || n,
       'fr',
       200 + (n * 37) % 1800,
       now() - (n || ' hours')::interval
FROM generate_series(1, 300) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO app.article_topics (article_id, topic_id, confidence)
SELECT a.id, t.id, 0.9
FROM app.articles a
JOIN app.topics t ON t.id = 1 + (substring(a.id FROM 6)::int % (SELECT count(*) FROM app.topics))
WHERE a.id LIKE 'a_dev%'
ON CONFLICT DO NOTHING;
