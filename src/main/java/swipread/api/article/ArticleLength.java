package swipread.api.article;

/**
 * Longueur d'un article : son nombre de mots, et s'il n'a été compté que sur le titre et le résumé du flux (page de
 * l'article illisible, flux sans le texte).
 */
public record ArticleLength(int wordCount, boolean fromSummary) {

    /**
     * Le nombre de mots qui permet d'estimer un temps de lecture, null s'il ne vient que du résumé.
     */
    public Integer readingWordCount() {
        return fromSummary ? null : wordCount;
    }
}
