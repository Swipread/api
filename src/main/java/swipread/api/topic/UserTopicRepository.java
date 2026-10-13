package swipread.api.topic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserTopicRepository extends JpaRepository<UserTopic, UserTopic.Id> {

    @Modifying
    @Query("delete from UserTopic ut where ut.id.userId = :userId")
    void deleteByUserId(String userId);
}
