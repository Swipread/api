package swipread.api.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swipread.api.article.ArticleQueryService;
import swipread.api.article.dto.ArticleResponse;
import swipread.api.user.dto.SavedArticleResponse;
import swipread.api.user.dto.SavedPageResponse;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SavedArticleService {

    private final JdbcTemplate jdbc;
    private final ArticleQueryService articles;

    public SavedArticleService(JdbcTemplate jdbc, ArticleQueryService articles) {
        this.jdbc = jdbc;
        this.articles = articles;
    }

    @Transactional(readOnly = true)
    public SavedPageResponse page(String userId, String cursor, int limit) {
        ArticleCursor after = cursor == null ? null : ArticleCursor.decode(cursor);
        Timestamp afterAt = after == null ? null : Timestamp.from(after.at());
        List<Row> rows = jdbc.query("""
                        SELECT article_id, saved_at, liked_at IS NOT NULL AS liked FROM app.user_articles
                        WHERE user_id = ? AND saved_at IS NOT NULL AND hidden_at IS NULL
                          AND (?::timestamptz IS NULL OR (saved_at, article_id) < (?::timestamptz, ?))
                        ORDER BY saved_at DESC, article_id DESC
                        LIMIT ?
                        """,
                (rs, i) -> new Row(new ArticleCursor(rs.getTimestamp("saved_at").toInstant(), rs.getString("article_id")),
                        rs.getBoolean("liked")),
                userId, afterAt, afterAt, after == null ? null : after.articleId(), limit + 1);

        boolean hasMore = rows.size() > limit;
        List<Row> page = hasMore ? rows.subList(0, limit) : rows;
        Map<String, ArticleResponse> byId = articles.hydrate(page.stream().map(row -> row.position().articleId()).toList())
                .stream().collect(Collectors.toMap(ArticleResponse::id, Function.identity()));
        List<SavedArticleResponse> items = page.stream()
                .filter(row -> byId.containsKey(row.position().articleId()))
                .map(row -> new SavedArticleResponse(byId.get(row.position().articleId()), row.position().at(),
                        row.liked()))
                .toList();
        return new SavedPageResponse(items, hasMore ? page.getLast().position().encode() : null);
    }

    private record Row(ArticleCursor position, boolean liked) {
    }
}
