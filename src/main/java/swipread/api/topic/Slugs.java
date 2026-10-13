package swipread.api.topic;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Slug d'un thème : minuscules ASCII, chiffres et tirets (« Santé & bien-être » donne « sante-bien-etre »).
 */
final class Slugs {

    private Slugs() {
    }

    static String of(String label) {
        String ascii = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
    }
}
