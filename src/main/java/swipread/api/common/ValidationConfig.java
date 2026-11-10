package swipread.api.common;

import jakarta.validation.MessageInterpolator;
import org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Locale;

/**
 * Messages de validation toujours en anglais, comme les autres {@code detail} de l'API. Sans cela, Hibernate Validator
 * suit l'en-tête Accept-Language ou la langue de la JVM, et les réponses mélangent français et anglais. Les clients
 * s'appuient sur {@code errors[].field} et {@code errors[].code}, pas sur le message.
 */
@Configuration(proxyBeanMethods = false)
class ValidationConfig {

    static final Locale MESSAGES_LOCALE = Locale.ENGLISH;

    // Même nom que le validateur de Spring Boot, qui s'efface alors : MVC et JPA utilisent celui-ci
    @Bean
    static LocalValidatorFactoryBean defaultValidator() {
        LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
        factory.setMessageInterpolator(new FixedLocaleInterpolator(new ResourceBundleMessageInterpolator()));
        return factory;
    }

    /**
     * Ignore la langue demandée : Spring appelle la variante avec Locale en lui passant la langue de la requête.
     */
    record FixedLocaleInterpolator(MessageInterpolator delegate) implements MessageInterpolator {

        @Override
        public String interpolate(String messageTemplate, Context context) {
            return delegate.interpolate(messageTemplate, context, MESSAGES_LOCALE);
        }

        @Override
        public String interpolate(String messageTemplate, Context context, Locale locale) {
            return delegate.interpolate(messageTemplate, context, MESSAGES_LOCALE);
        }
    }
}
