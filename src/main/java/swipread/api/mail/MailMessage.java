package swipread.api.mail;

/**
 * Un mail prêt à partir : version texte et version HTML du même contenu.
 */
public record MailMessage(String to, String subject, String text, String html) {
}
