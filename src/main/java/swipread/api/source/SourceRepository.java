package swipread.api.source;

import org.springframework.data.jpa.repository.JpaRepository;
import swipread.api.article.Source;

import java.util.List;

interface SourceRepository extends JpaRepository<Source, Integer> {

    List<Source> findAllByOrderByNameAsc();

    boolean existsByFeedUrl(String feedUrl);
}
