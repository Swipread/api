package swipread.api.article;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swipread.api.article.dto.ArticleResponse;
import swipread.api.topic.dto.TopicResponse;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ArticleQueryService {

    private final ArticleRepository articles;

    public ArticleQueryService(ArticleRepository articles) {
        this.articles = articles;
    }

    /**
     * Hydrate des IDs en conservant leur ordre. Les IDs absents de la base sont ignorés.
     */
    @Transactional(readOnly = true)
    public List<ArticleResponse> hydrate(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, List<TopicResponse>> topics = articles.findTopics(ids).stream()
                .collect(Collectors.groupingBy(ArticleRepository.ArticleTopic::getArticleId,
                        Collectors.mapping(t -> new TopicResponse(t.getId(), t.getSlug(), t.getLabel()),
                                Collectors.toList())));
        Map<String, ArticleResponse> byId = articles.findWithSourceByIdIn(ids).stream()
                .collect(Collectors.toMap(Article::getId,
                        article -> ArticleResponse.from(article, topics.getOrDefault(article.getId(), List.of()))));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> wordCounts(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return articles.findWordCounts(ids).stream()
                .collect(Collectors.toMap(ArticleRepository.WordCount::getId, ArticleRepository.WordCount::getWordCount,
                        (a, b) -> a));
    }
}
