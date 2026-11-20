package swipread.api.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param from     adresse d'expédition ; son domaine doit être celui que le serveur mail accepte et signe
 * @param fromName nom affiché de l'expéditeur
 */
@Validated
@ConfigurationProperties("swipread.mail")
public record MailProperties(@NotBlank @Email String from, @NotBlank String fromName, Duration relayInterval) {
}
