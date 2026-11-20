package swipread.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.multipart.MultipartResolver;
import swipread.api.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class MultipartDisabledIntegrationTest extends IntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void multipartRequestsAreNeverParsed() {
        // Sans MultipartResolver, le DispatcherServlet ne lit jamais les en-têtes des parties (CVE-2026-114938)
        assertThat(context.getBeanNamesForType(MultipartResolver.class)).isEmpty();
    }
}
