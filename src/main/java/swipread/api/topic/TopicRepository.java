package swipread.api.topic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TopicRepository extends JpaRepository<Topic, Integer> {

    List<Topic> findAllByOrderByLabelAsc();

    boolean existsBySlug(String slug);

    @Query(value = """
            SELECT t.id AS id, t.slug AS slug, t.label AS label,
                   (SELECT count(*) FROM app.article_topics at WHERE at.topic_id = t.id) AS articleCount,
                   (SELECT count(*) FROM app.user_topics ut WHERE ut.topic_id = t.id) AS readerCount
            FROM app.topics t
            WHERE CAST(:id AS integer) IS NULL OR t.id = :id
            ORDER BY t.label
            """, nativeQuery = true)
    List<TopicStats> findStats(Integer id);

    interface TopicStats {
        int getId();

        String getSlug();

        String getLabel();

        long getArticleCount();

        long getReaderCount();
    }

    @Query("select t from Topic t where t.id in (select ut.id.topicId from UserTopic ut where ut.id.userId = :userId) order by t.label")
    List<Topic> findByUserId(String userId);
}
