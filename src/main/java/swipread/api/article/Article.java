package swipread.api.article;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Lecture seule côté backend (écrit par l'ingestion). La colonne embedding n'est volontairement pas mappée.
 */
@Entity
@Table(name = "articles", schema = "app")
public class Article {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id")
    private Source source;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String summary;

    @Column(name = "image_url")
    private String imageUrl;

    private String author;

    @Column(nullable = false)
    private String language;

    @Column(name = "word_count", nullable = false)
    private int wordCount;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    protected Article() {
    }

    public String getId() {
        return id;
    }

    public Source getSource() {
        return source;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getAuthor() {
        return author;
    }

    public String getLanguage() {
        return language;
    }

    public int getWordCount() {
        return wordCount;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
