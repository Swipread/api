package swipread.api.mail;

import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mise en forme commune des mails de Swipread : un titre, des paragraphes, éventuellement un bouton (lien), et une
 * note de bas de page. Produit la version texte et la version HTML ; tout le texte variable est échappé en HTML.
 *
 * @param title      titre, repris en objet du mail
 * @param paragraphs paragraphes de texte brut
 * @param action     bouton facultatif
 * @param footnote   note discrète sous le contenu (pourquoi ce mail, que faire si on ne l'attendait pas)
 */
public record MailTemplate(String title, List<String> paragraphs, Action action, String footnote) {

    public record Action(String label, String url) {
    }

    public MailMessage to(String recipient) {
        return new MailMessage(recipient, title, text(), html());
    }

    String text() {
        StringBuilder text = new StringBuilder(title).append("\n\n");
        paragraphs.forEach(p -> text.append(p).append("\n\n"));
        if (action != null) {
            text.append(action.label()).append(" : ").append(action.url()).append("\n\n");
        }
        if (footnote != null) {
            text.append("--\n").append(footnote).append("\n");
        }
        return text.append("\nSwipread\n").toString();
    }

    String html() {
        String body = paragraphs.stream()
                .map(p -> "<p style=\"margin:0 0 16px\">" + escape(p) + "</p>")
                .collect(Collectors.joining());
        String button = action == null ? "" : """
                <p style="margin:24px 0"><a href="%s" style="display:inline-block;padding:12px 20px;border-radius:999px;\
                background:#1c1c1e;color:#ffffff;text-decoration:none;font-weight:600">%s</a></p>
                <p style="margin:0 0 16px;font-size:13px;color:#6e6e73">Si le bouton ne fonctionne pas, copiez ce lien \
                dans votre navigateur :<br><span style="word-break:break-all">%s</span></p>"""
                .formatted(escape(action.url()), escape(action.label()), escape(action.url()));
        String note = footnote == null ? "" : """
                <p style="margin:24px 0 0;padding-top:16px;border-top:1px solid #e5e5ea;font-size:13px;color:#6e6e73">%s</p>"""
                .formatted(escape(footnote));
        return """
                <!doctype html>
                <html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">\
                <title>%s</title></head>
                <body style="margin:0;padding:24px;background:#f5f5f7;font-family:-apple-system,BlinkMacSystemFont,\
                'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:15px;line-height:1.5;color:#1c1c1e">
                <div style="max-width:520px;margin:0 auto;padding:32px;background:#ffffff;border-radius:16px">
                <p style="margin:0 0 24px;font-weight:700;font-size:17px">Swipread</p>
                <h1 style="margin:0 0 16px;font-size:20px">%s</h1>
                %s%s%s
                </div>
                </body></html>
                """.formatted(escape(title), escape(title), body, button, note);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
