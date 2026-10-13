package swipread.api.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swipread.api.article.ArticleQueryService;
import swipread.api.article.dto.ArticleResponse;
import swipread.api.user.dto.HistoryItemResponse;
import swipread.api.user.dto.HistoryPageResponse;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Historique de lecture : articles ouverts (événement open ou read), du plus récemment ouvert au plus ancien.
 */
@Service
public class HistoryService {

    private final JdbcTemplate jdbc;
    private final ArticleQueryService articles;

    public HistoryService(JdbcTemplate jdbc, ArticleQueryService articles) {
        this.jdbc = jdbc;
        this.articles = articles;
    }

    @Transactional(readOnly = true)
    public HistoryPageResponse page(String userId, String cursor, int limit) {
        ArticleCursor after = cursor == null ? null : ArticleCursor.decode(cursor);
        Timestamp afterAt = after == null ? null : Timestamp.from(after.at());
        List<Row> rows = jdbc.query("""
                        SELECT article_id, opened_at, liked_at IS NOT NULL AS liked, saved_at IS NOT NULL AS saved
                        FROM app.user_articles
                        WHERE user_id = ? AND opened_at IS NOT NULL AND hidden_at IS NULL
                          AND (?::timestamptz IS NULL OR (opened_at, article_id) < (?::timestamptz, ?))
                        ORDER BY opened_at DESC, article_id DESC
                        LIMIT ?
                        """,
                (rs, i) -> new Row(new ArticleCursor(rs.getTimestamp("opened_at").toInstant(), rs.getString("article_id")),
                        rs.getBoolean("liked"), rs.getBoolean("saved")),
                userId, afterAt, afterAt, after == null ? null : after.articleId(), limit + 1);

        boolean hasMore = rows.size() > limit;
        List<Row> page = hasMore ? rows.subList(0, limit) : rows;
        Map<String, ArticleResponse> byId = articles.hydrate(page.stream().map(row -> row.position().articleId()).toList())
                .stream().collect(Collectors.toMap(ArticleResponse::id, Function.identity()));
        List<HistoryItemResponse> items = page.stream()
                .filter(row -> byId.containsKey(row.position().articleId()))
                .map(row -> new HistoryItemResponse(byId.get(row.position().articleId()), row.position().at(),
                        row.liked(), row.saved()))
                .toList();
        return new HistoryPageResponse(items, hasMore ? page.getLast().position().encode() : null);
    }

    private record Row(ArticleCursor position, boolean liked, boolean saved) {
    }
}
