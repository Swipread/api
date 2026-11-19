package swipread.api.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import swipread.api.common.IdGenerator;
import swipread.api.source.SourceFetchState;
import swipread.api.source.SourceTopics;
import swipread.api.topic.dto.TopicResponse;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ingestion (contrat v1.5) : lit le flux de chaque source active qui a des thèmes et enregistre ses nouveaux
 * articles, avec les thèmes de la source (confidence 1.0, 0.9, 0.8 dans leur ordre).
 */
@Service
public class IngestionService {

    /** Clé du verrou consultatif PostgreSQL qui empêche deux passes simultanées (plusieurs instances de l'API). */
    static final long LOCK_KEY = 0x5357_5052_4541_4401L;
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final float[] CONFIDENCE = {1.0f, 0.9f, 0.8f};

    /** Bilan d'une passe ; skipped si une autre instance faisait déjà la sienne. */
    public record Run(boolean skipped, int sources, int articles, int failures) {
    }

    record SourceToRead(int id, String feedUrl, String language, String etag, String lastModified) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final FeedReader feeds;
    private final PageReader pages;
    private final SourceTopics sourceTopics;
    private final SourceFetchState fetchState;
    private final IdGenerator ids;
    private final IngestionProperties properties;
    private final Clock clock;

    IngestionService(JdbcTemplate jdbc, TransactionTemplate transaction, FeedReader feeds, PageReader pages,
                     SourceTopics sourceTopics, SourceFetchState fetchState, IdGenerator ids,
                     IngestionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.feeds = feeds;
        this.pages = pages;
        this.sourceTopics = sourceTopics;
        this.fetchState = fetchState;
        this.ids = ids;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Une passe sur toutes les sources, sous un verrou consultatif tenu par une connexion dédiée pendant toute la
     * passe : une seconde instance qui démarre la sienne en même temps ne fait rien. Une source en échec n'arrête pas
     * les autres.
     */
    public Run runOnce() {
        return jdbc.execute((ConnectionCallback<Run>) connection -> {
            try (PreparedStatement lock = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                lock.setLong(1, LOCK_KEY);
                try (ResultSet rs = lock.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        log.info("Ingestion skipped: another instance is running it");
                        return new Run(true, 0, 0, 0);
                    }
                }
            }
            try {
                return readAll();
            } finally {
                try (PreparedStatement unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, LOCK_KEY);
                    unlock.execute();
                }
            }
        });
    }

    private Run readAll() {
        pages.startRun();
        List<SourceToRead> sources = jdbc.query("""
                        SELECT s.id, s.feed_url, s.language, s.etag, s.last_modified
                        FROM app.sources s
                        WHERE s.active AND EXISTS (SELECT 1 FROM app.source_topics st WHERE st.source_id = s.id)
                        ORDER BY s.id
                        """,
                (rs, row) -> new SourceToRead(rs.getInt("id"), rs.getString("feed_url"), rs.getString("language"),
                        rs.getString("etag"), rs.getString("last_modified")));
        int articles = 0;
        int failures = 0;
        for (SourceToRead source : sources) {
            try {
                articles += read(source);
            } catch (FeedReader.FeedReadException e) {
                failures++;
                fetchState.recordError(source.id(), e.getMessage());
                log.info("Ingestion of source {} failed: {}", source.id(), e.getMessage());
            } catch (RuntimeException e) {
                failures++;
                fetchState.recordError(source.id(), "Ingestion failed");
                log.warn("Ingestion of source {} failed", source.id(), e);
            }
        }
        log.info("Ingestion: {} sources read, {} new articles, {} failures", sources.size(), articles, failures);
        return new Run(false, sources.size(), articles, failures);
    }

    /** @return le nombre d'articles ajoutés */
    int read(SourceToRead source) {
        Instant now = clock.instant();
        FeedReader.Result feed = feeds.read(source.feedUrl(), source.etag(), source.lastModified());
        if (feed.notModified()) {
            fetchState.recordFetch(source.id(), now, source.etag(), source.lastModified());
            return 0;
        }
        List<TopicResponse> topics = sourceTopics.of(source.id());
        Instant oldest = now.minus(properties.maxAge());
        // Une adresse par article, la plus récente des entrées qui la partagent ; date absente ou future = maintenant
        Map<String, FeedReader.Item> byUrl = new LinkedHashMap<>();
        for (FeedReader.Item item : feed.items()) {
            Instant published = item.publishedAt() == null || item.publishedAt().isAfter(now) ? now : item.publishedAt();
            if (published.isBefore(oldest)) {
                continue;
            }
            FeedReader.Item dated = new FeedReader.Item(item.url(), item.title(), item.summary(), item.contentText(),
                    item.author(), published, item.imageUrl());
            byUrl.merge(item.url(), dated, (a, b) -> a.publishedAt().isAfter(b.publishedAt()) ? a : b);
        }
        Set<String> known = known(byUrl.keySet());
        List<FeedReader.Item> fresh = byUrl.values().stream()
                .filter(item -> !known.contains(item.url()))
                .sorted(Comparator.comparing(FeedReader.Item::publishedAt).reversed())
                .limit(properties.maxNewPerSource())
                .toList();
        int added = 0;
        for (FeedReader.Item item : fresh) {
            if (save(source, item, topics, now)) {
                added++;
            }
        }
        fetchState.recordFetch(source.id(), now, feed.etag(), feed.lastModified());
        return added;
    }

    private Set<String> known(Set<String> urls) {
        if (urls.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("SELECT url FROM app.articles WHERE url = ANY (?)", String.class,
                (Object) urls.toArray(String[]::new)));
    }

    /**
     * Lit la page (hors transaction), puis enregistre l'article et ses thèmes ensemble. Le nombre de mots vient de la
     * page, sinon du contenu du flux, et vaut au moins 1.
     *
     * @return faux si l'adresse a été enregistrée entre-temps
     */
    private boolean save(SourceToRead source, FeedReader.Item item, List<TopicResponse> topics, Instant now) {
        Optional<PageReader.Page> page = pages.read(item.url(), item.title());
        int pageWords = page.map(PageReader.Page::wordCount).orElse(0);
        int feedWords = ArticleText.countWords(item.contentText().isEmpty()
                ? item.title() + " " + item.summary() : item.contentText());
        int wordCount = Math.max(1, pageWords > 0 ? pageWords : feedWords);
        String image = item.imageUrl() != null ? item.imageUrl() : page.map(PageReader.Page::imageUrl).orElse(null);
        String id = ids.newArticleId();
        Boolean inserted = transaction.execute(status -> {
            int rows = jdbc.update("""
                            INSERT INTO app.articles (id, source_id, url, title, summary, image_url, author, language,
                                                      word_count, published_at, ingested_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (url) DO NOTHING
                            """,
                    id, source.id(), item.url(), item.title(), item.summary(), image, item.author(), source.language(),
                    wordCount, Timestamp.from(item.publishedAt()), Timestamp.from(now));
            if (rows == 0) {
                return false;
            }
            for (int i = 0; i < topics.size() && i < CONFIDENCE.length; i++) {
                jdbc.update("INSERT INTO app.article_topics (article_id, topic_id, confidence) VALUES (?, ?, ?)",
                        id, topics.get(i).id(), CONFIDENCE[i]);
            }
            return true;
        });
        return Boolean.TRUE.equals(inserted);
    }
}
