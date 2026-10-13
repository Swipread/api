package swipread.api.feed;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

@Configuration(proxyBeanMethods = false)
class FeedConfig {

    static final String REFILL_EXECUTOR = "feedRefillExecutor";

    @Bean(REFILL_EXECUTOR)
    TaskExecutor feedRefillExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("feed-refill-");
        executor.setVirtualThreads(true);
        // Borne le nombre d'appels simultanés à la reco
        executor.setConcurrencyLimit(64);
        return executor;
    }
}
