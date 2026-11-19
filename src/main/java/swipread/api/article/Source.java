package swipread.api.article;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Flux ingéré (RSS ou API), géré par l'administration.
 */
@Entity
@Table(name = "sources", schema = "app")
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String name;

    @Column(name = "site_url", nullable = false)
    private String siteUrl;

    @Column(name = "feed_url", nullable = false, unique = true)
    private String feedUrl;

    @Column(nullable = false)
    private String language;

    // Conditions de réutilisation des contenus
    private String license;

    @Column(nullable = false)
    private boolean active = true;

    // Lecture par l'ingestion (V8) : validateurs HTTP du flux, date de la dernière lecture réussie, dernière erreur
    private String etag;

    @Column(name = "last_modified")
    private String lastModified;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @Column(name = "last_error")
    private String lastError;

    protected Source() {
    }

    public Source(String name, String siteUrl, String feedUrl, String language, String license) {
        this.name = name;
        this.siteUrl = siteUrl;
        this.feedUrl = feedUrl;
        this.language = language;
        this.license = license;
    }

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSiteUrl() {
        return siteUrl;
    }

    public String getFeedUrl() {
        return feedUrl;
    }

    public String getLanguage() {
        return language;
    }

    public String getLicense() {
        return license;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getEtag() {
        return etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    public Instant getLastFetchedAt() {
        return lastFetchedAt;
    }

    public String getLastError() {
        return lastError;
    }

    /**
     * Lecture réussie du flux : les validateurs HTTP servent à la lecture conditionnelle suivante.
     */
    public void recordFetch(Instant at, String etag, String lastModified) {
        this.lastFetchedAt = at;
        this.etag = etag;
        this.lastModified = lastModified;
        this.lastError = null;
    }

    /**
     * Lecture en échec : la date de la dernière lecture réussie est gardée.
     */
    public void recordError(String error) {
        this.lastError = error;
    }
}
