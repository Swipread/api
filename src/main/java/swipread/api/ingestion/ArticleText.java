package swipread.api.ingestion;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Comparator;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texte des flux et des pages : HTML retiré, résumé, nombre de mots du texte principal, image de partage.
 */
final class ArticleText {

    static final int SUMMARY_MAX = 400;
    static final int TITLE_MAX = 300;

    // Un mot : lettres ou chiffres, avec apostrophes et traits d'union internes (« aujourd'hui », « porte-parole »)
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*");
    // Ce qui n'est pas le texte de l'article
    private static final String NOT_CONTENT =
            "script, style, noscript, template, svg, nav, header, footer, aside, form, iframe, button, figcaption";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ArticleText() {
    }

    /**
     * Nombre de mots de l'article d'après la page : celui que la page déclare en schema.org (JSON-LD wordCount, sinon
     * les mots de articleBody) s'il existe, plus fiable ; sinon celui de {@link #mainText(Document)}.
     */
    static int pageWordCount(Document page) {
        OptionalInt declared = declaredWordCount(page);
        return declared.isPresent() ? declared.getAsInt() : countWords(mainText(page));
    }

    /** wordCount, ou mots de articleBody, du premier objet schema.org de type article des blocs JSON-LD de la page. */
    static OptionalInt declaredWordCount(Document page) {
        for (Element script : page.select("script[type=application/ld+json]")) {
            try {
                OptionalInt count = declaredWordCount(JSON.readTree(script.data()));
                if (count.isPresent()) {
                    return count;
                }
            } catch (JacksonException e) {
                // JSON-LD invalide : bloc ignoré
            }
        }
        return OptionalInt.empty();
    }

    private static OptionalInt declaredWordCount(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode item : node) {
                OptionalInt count = declaredWordCount(item);
                if (count.isPresent()) {
                    return count;
                }
            }
            return OptionalInt.empty();
        }
        if (!node.isObject()) {
            return OptionalInt.empty();
        }
        if (isArticleType(node.get("@type"))) {
            JsonNode wordCount = node.get("wordCount");
            if (wordCount != null) {
                int value = wordCount.isNumber() ? wordCount.asInt() : parsePositive(wordCount.asString(""));
                if (value > 0) {
                    return OptionalInt.of(value);
                }
            }
            JsonNode body = node.get("articleBody");
            if (body != null && body.isString()) {
                int value = countWords(text(body.asString()));
                if (value > 0) {
                    return OptionalInt.of(value);
                }
            }
        }
        // @graph et objets imbriqués (mainEntity…)
        for (JsonNode child : node) {
            if (child.isContainer()) {
                OptionalInt count = declaredWordCount(child);
                if (count.isPresent()) {
                    return count;
                }
            }
        }
        return OptionalInt.empty();
    }

    /** Article, NewsArticle, ReportageNewsArticle, BlogPosting… (un type ou une liste de types). */
    private static boolean isArticleType(JsonNode type) {
        if (type == null) {
            return false;
        }
        if (type.isArray()) {
            for (JsonNode item : type) {
                if (isArticleType(item)) {
                    return true;
                }
            }
            return false;
        }
        String name = type.asString("");
        return name.endsWith("Article") || name.equals("BlogPosting") || name.equals("Report");
    }

    private static int parsePositive(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Texte d'un fragment HTML (ou de texte avec entités), espaces normalisés. */
    static String text(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return Jsoup.parse(html).text().strip();
    }

    /** Résumé d'au plus {@link #SUMMARY_MAX} caractères, coupé à la fin d'un mot. */
    static String summary(String text) {
        return truncate(text, SUMMARY_MAX);
    }

    static String title(String text) {
        return truncate(text, TITLE_MAX);
    }

    static int countWords(String text) {
        if (text == null) {
            return 0;
        }
        Matcher matcher = WORD.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Texte principal d'une page : l'élément article le plus long, sinon main, sinon le corps, sans la navigation,
     * les en-têtes, pieds de page, encadrés et formulaires.
     */
    static String mainText(Document page) {
        Document copy = page.clone();
        copy.select(NOT_CONTENT).remove();
        // Les cartes « à lire aussi » sont souvent des article imbriqués dans celui de la page : seuls les article
        // qui ne sont dans aucun autre sont candidats, et on leur retire ceux qu'ils contiennent
        Element main = copy.select("article").stream()
                .filter(article -> article.parents().stream().noneMatch(parent -> parent.nameIs("article")))
                .map(ArticleText::withoutNestedArticles)
                .max(Comparator.comparingInt(element -> element.text().length()))
                .orElseGet(() -> {
                    Element mainElement = copy.selectFirst("main");
                    return mainElement != null ? withoutNestedArticles(mainElement) : copy.body();
                });
        return main == null ? "" : main.text();
    }

    /**
     * Vrai si la page parle bien de l'article : les 4 premiers mots de son titre (assez pour reconnaître l'article, peu
     * pour tolérer un titre retouché) apparaissent dans le titre de la page,
     * og:title, un h1 ou le texte. Écarte les pages de défi anti-robot, de consentement ou d'erreur servies en 200.
     */
    static boolean isAbout(Document page, String title) {
        String[] words = normalize(title).split(" ");
        if (words.length == 0 || words[0].isEmpty()) {
            return false;
        }
        String prefix = String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 4)));
        Element ogTitle = page.selectFirst("meta[property=og:title]");
        String haystack = normalize(page.title() + " " + (ogTitle == null ? "" : ogTitle.attr("content")) + " "
                + page.select("h1").text() + " " + page.body().text());
        return haystack.contains(prefix);
    }

    /** Minuscules, sans accents, mots séparés par une espace. */
    static String normalize(String text) {
        String plain = java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT);
        return plain.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static Element withoutNestedArticles(Element element) {
        Element copy = element.clone();
        copy.select("article").stream().filter(nested -> nested != copy).forEach(Element::remove);
        return copy;
    }

    /** Image de partage de la page (og:image, sinon twitter:image), en adresse http(s) absolue, ou null. */
    static String shareImage(Document page, URI pageUri) {
        for (String selector : new String[]{"meta[property=og:image]", "meta[name=twitter:image]"}) {
            Element meta = page.selectFirst(selector);
            if (meta != null) {
                String url = absoluteHttpUrl(pageUri, meta.attr("content"));
                if (url != null) {
                    return url;
                }
            }
        }
        return null;
    }

    /** Adresse http(s) absolue, sans fragment, d'au plus 2048 caractères, ou null. */
    static String absoluteHttpUrl(URI base, String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        try {
            URI uri = base == null ? new URI(link.strip()) : base.resolve(link.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
                return null;
            }
            // Sans le fragment, en gardant l'encodage d'origine du reste
            String url = uri.toASCIIString();
            int hash = url.indexOf('#');
            if (hash >= 0) {
                url = url.substring(0, hash);
            }
            return url.length() > 2048 ? null : url;
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf(' ', max - 1);
        return (cut > max / 2 ? text.substring(0, cut) : text.substring(0, max - 1)).strip() + "…";
    }
}
