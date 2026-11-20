package swipread.api.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import swipread.api.mail.MailMessage;
import swipread.api.mail.MailTemplate;
import swipread.api.user.User;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Les mails du compte, en français. Les liens mènent au web (swipread.web.base-url), qui appelle l'API.
 */
@Component
class AccountMails {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH'h'mm", Locale.FRENCH);

    private final WebProperties web;

    AccountMails(WebProperties web) {
        this.web = web;
    }

    MailMessage verifyEmail(User user, String token) {
        return new MailTemplate("Confirmez votre adresse e-mail",
                List.of("Bonjour " + user.getDisplayName() + ",",
                        "Pour confirmer que cette adresse est bien la vôtre, cliquez sur le bouton ci-dessous. "
                                + "Le lien est valable 24 heures."),
                new MailTemplate.Action("Confirmer mon adresse", link("/verifier-email", token)),
                "Vous n'avez pas créé de compte Swipread ? Ignorez ce message : sans votre clic, l'adresse reste "
                        + "non confirmée.")
                .to(user.getEmail());
    }

    MailMessage passwordChanged(User user, Instant when) {
        return new MailTemplate("Votre mot de passe a été modifié",
                List.of("Bonjour " + user.getDisplayName() + ",",
                        "Le mot de passe de votre compte Swipread a été modifié le " + WHEN.format(when.atZone(PARIS))
                                + " (heure de Paris). Vos autres appareils ont été déconnectés.",
                        "Si vous n'êtes pas à l'origine de ce changement, choisissez un nouveau mot de passe avec "
                                + "« Mot de passe oublié » sur la page de connexion."),
                null,
                "Ce message vous est envoyé à chaque changement de mot de passe, pour votre sécurité.")
                .to(user.getEmail());
    }

    private String link(String path, String token) {
        return UriComponentsBuilder.fromUri(web.baseUrl()).path(path).queryParam("token", token).build().toUriString();
    }
}
