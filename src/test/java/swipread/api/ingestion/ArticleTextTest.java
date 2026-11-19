package swipread.api.ingestion;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleTextTest {

    @Test
    void stripsHtmlAndEntities() {
        assertThat(ArticleText.text("<p>Le <b>vote</b> &amp; ses&nbsp;suites</p>")).isEqualTo("Le vote & ses suites");
        assertThat(ArticleText.text(null)).isEmpty();
        assertThat(ArticleText.text("  ")).isEmpty();
    }

    @Test
    void countsWordsWithApostrophesAndHyphens() {
        assertThat(ArticleText.countWords("Aujourd'hui, le porte-parole a parlé 2 fois.")).isEqualTo(7);
        assertThat(ArticleText.countWords("— … !")).isZero();
        assertThat(ArticleText.countWords(null)).isZero();
    }

    @Test
    void cutsTheSummaryAtAWordBoundary() {
        String text = "mot ".repeat(200).strip();
        String summary = ArticleText.summary(text);
        assertThat(summary).hasSizeLessThanOrEqualTo(ArticleText.SUMMARY_MAX).endsWith("…").doesNotContain("mo…");
        assertThat(ArticleText.summary("court")).isEqualTo("court");
    }

    @Test
    void mainTextIsTheLongestArticleWithoutNavigation() {
        Document page = Jsoup.parse("""
                <html><body>
                  <nav>Accueil Rubriques Contact</nav>
                  <header>Le Journal</header>
                  <article>Court</article>
                  <article><h1>Titre</h1><p>Un deux trois quatre cinq.</p><aside>Lire aussi : autre chose</aside>
                    <script>var x = 1;</script></article>
                  <footer>Mentions légales</footer>
                </body></html>
                """);
        assertThat(ArticleText.mainText(page)).isEqualTo("Titre Un deux trois quatre cinq.");
        // Le document d'origine n'est pas modifié
        assertThat(page.select("nav")).hasSize(1);
    }

    @Test
    void mainTextLeavesOutArticleCardsNestedInTheArticle() {
        Document page = Jsoup.parse("""
                <body><article id="page"><p>Le vrai texte de la page.</p>
                  <article>A lire aussi : un sujet bien plus long que le texte lui-même, avec beaucoup de mots</article>
                  <article>Et encore un autre sujet</article>
                </article></body>
                """);
        assertThat(ArticleText.mainText(page)).isEqualTo("Le vrai texte de la page.");
    }

    @Test
    void recognisesThePageOfTheArticle() {
        Document article = Jsoup.parse("<head><title>L’Europe va-t-elle avoir son propre SpaceX ? - franceinfo</title></head>"
                + "<body><p>Texte</p></body>");
        assertThat(ArticleText.isAbout(article, "L'Europe va-t-elle avoir son propre SpaceX ?")).isTrue();
        assertThat(ArticleText.isAbout(Jsoup.parse("<body><h1>Épidémie : le péril microbien</h1></body>"),
                "Epidemie, le peril microbien n'est pas ineluctable")).isTrue();
        Document challenge = Jsoup.parse("<head><title>Client Challenge</title></head><body>Please enable JavaScript</body>");
        assertThat(ArticleText.isAbout(challenge, "Epidemia, le péril microbien n'est pas inéluctable")).isFalse();
        assertThat(ArticleText.isAbout(article, "")).isFalse();
    }

    @Test
    void prefersTheWordCountThePageDeclares() {
        String article = "<body><article>" + "mot ".repeat(50) + "</article></body>";
        assertThat(ArticleText.pageWordCount(Jsoup.parse(ld("{\"@type\": \"NewsArticle\", \"wordCount\": 346}") + article)))
                .isEqualTo(346);
        assertThat(ArticleText.pageWordCount(Jsoup.parse(ld("{\"@type\": [\"Article\"], \"wordCount\": \"12\"}") + article)))
                .isEqualTo(12);
        assertThat(ArticleText.pageWordCount(Jsoup.parse(
                ld("{\"@graph\": [{\"@type\": \"WebPage\"}, {\"@type\": \"BlogPosting\", \"articleBody\": \"Un deux trois\"}]}") + article)))
                .isEqualTo(3);
        // Pas un article, JSON invalide ou wordCount nul : texte principal de la page
        assertThat(ArticleText.pageWordCount(Jsoup.parse(ld("{\"@type\": \"Organization\", \"wordCount\": 9}") + article)))
                .isEqualTo(50);
        assertThat(ArticleText.pageWordCount(Jsoup.parse(ld("{pas du json") + article))).isEqualTo(50);
        assertThat(ArticleText.pageWordCount(Jsoup.parse(ld("{\"@type\": \"NewsArticle\", \"wordCount\": 0}") + article)))
                .isEqualTo(50);
    }

    private static String ld(String json) {
        return "<head><script type=\"application/ld+json\">" + json + "</script></head>";
    }

    @Test
    void mainTextFallsBackToMainThenBody() {
        assertThat(ArticleText.mainText(Jsoup.parse("<body><nav>Menu</nav><main>Texte principal</main><p>Autre</p></body>")))
                .isEqualTo("Texte principal");
        assertThat(ArticleText.mainText(Jsoup.parse("<body><nav>Menu</nav><p>Tout le corps</p></body>")))
                .isEqualTo("Tout le corps");
    }

    @Test
    void findsTheShareImage() {
        URI page = URI.create("https://journal.example/2026/article");
        assertThat(ArticleText.shareImage(Jsoup.parse("<head><meta property='og:image' content='/img/a.jpg'></head>"), page))
                .isEqualTo("https://journal.example/img/a.jpg");
        assertThat(ArticleText.shareImage(Jsoup.parse("<head><meta name='twitter:image' content='https://cdn.example/b.png'></head>"), page))
                .isEqualTo("https://cdn.example/b.png");
        assertThat(ArticleText.shareImage(Jsoup.parse("<head><meta property='og:image' content='javascript:alert(1)'></head>"), page))
                .isNull();
        assertThat(ArticleText.shareImage(Jsoup.parse("<head></head>"), page)).isNull();
    }

    @Test
    void keepsOnlyAbsoluteHttpUrlsWithoutFragment() {
        URI feed = URI.create("https://journal.example/rss");
        assertThat(ArticleText.absoluteHttpUrl(feed, "/a/1?x=1#top")).isEqualTo("https://journal.example/a/1?x=1");
        assertThat(ArticleText.absoluteHttpUrl(feed, "https://autre.example/b%2Fc")).isEqualTo("https://autre.example/b%2Fc");
        assertThat(ArticleText.absoluteHttpUrl(feed, "ftp://journal.example/x")).isNull();
        assertThat(ArticleText.absoluteHttpUrl(feed, "mailto:a@b.c")).isNull();
        assertThat(ArticleText.absoluteHttpUrl(feed, "https://x.example/" + "a".repeat(2100))).isNull();
        assertThat(ArticleText.absoluteHttpUrl(feed, "pas une adresse avec espaces")).isNull();
        assertThat(ArticleText.absoluteHttpUrl(feed, null)).isNull();
    }
}
