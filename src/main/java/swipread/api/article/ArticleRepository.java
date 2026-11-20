package swipread.api.article;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ArticleRepository extends JpaRepository<Article, String> {

    boolean existsBySourceId(Integer sourceId);

    @Query("select a from Article a join fetch a.source where a.id in :ids")
    List<Article> findWithSourceByIdIn(Collection<String> ids);

    @Query(value = """
            SELECT id, word_count AS wordCount, word_count_from_summary AS fromSummary
            FROM app.articles WHERE id IN (:ids)
            """, nativeQuery = true)
    List<Length> findLengths(Collection<String> ids);

    @Query(value = """
            SELECT at.article_id AS articleId, t.id AS id, t.slug AS slug, t.label AS label
            FROM app.article_topics at
            JOIN app.topics t ON t.id = at.topic_id
            WHERE at.article_id IN (:ids)
            ORDER BY at.confidence DESC, t.label
            """, nativeQuery = true)
    List<ArticleTopic> findTopics(Collection<String> ids);

    interface ArticleTopic {
        String getArticleId();

        int getId();

        String getSlug();

        String getLabel();
    }

    interface Length {
        String getId();

        int getWordCount();

        boolean getFromSummary();
    }
}
