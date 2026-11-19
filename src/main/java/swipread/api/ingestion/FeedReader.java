package swipread.api.ingestion;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import jakarta.annotation.PreDestroy;
import org.jdom2.Element;
import org.springframework.stereotype.Component;
import swipread.api.source.SafeHttp;
import swipread.api.source.SafeHttpFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lit le flux d'une source, en ne le retéléchargeant que s'il a changé (ETag, Last-Modified).
 */
@Component
class FeedReader {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final String MEDIA_RSS = "http://search.yahoo.com/mrss/";

    /** Entrée du flux, avant le filtre d'âge et la lecture de sa page. */
    record Item(String url, String title, String summary, String contentText, String author, Instant publishedAt,
                String imageUrl) {
    }

    /** @param items vide quand le flux n'a pas changé */
    record Result(boolean notModified, List<Item> items, String etag, String lastModified) {
    }

    /** Lecture impossible ; le message est gardé dans app.sources.last_error. */
    static class FeedReadException extends RuntimeException {
        FeedReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SafeHttp http;

    FeedReader(SafeHttpFactory factory, IngestionProperties properties) {
        this.http = factory.create(Duration.ofSeconds(5), Duration.ofSeconds(10), properties.userAgent());
    }

    Result read(String feedUrl, String etag, String lastModified) {
        URI uri;
        try {
            uri = new URI(feedUrl);
        } catch (URISyntaxException e) {
            throw new FeedReadException("Feed address is not allowed", e);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.1");
        if (etag != null) {
            headers.put("If-None-Match", etag);
        }
        if (lastModified != null) {
            headers.put("If-Modified-Since", lastModified);
        }
        SafeHttp.Response response;
        try {
            response = http.get(uri, headers, MAX_BYTES);
        } catch (SafeHttp.FetchException e) {
            throw new FeedReadException(message(e.failure()), e);
        }
        if (response.status() == 304) {
            return new Result(true, List.of(), etag, lastModified);
        }
        if (!response.ok()) {
            throw new FeedReadException("Feed answered HTTP " + response.status(), null);
        }
        SyndFeed feed;
        try {
            // Rome refuse les DOCTYPE par défaut, ce qui protège des entités externes (XXE)
            feed = new SyndFeedInput().build(new XmlReader(new ByteArrayInputStream(response.body())));
        } catch (FeedException | IOException | IllegalArgumentException e) {
            throw new FeedReadException("Not a valid RSS or Atom feed", e);
        }
        List<Item> items = new ArrayList<>();
        for (SyndEntry entry : feed.getEntries()) {
            Item item = item(entry, response.uri());
            if (item != null) {
                items.add(item);
            }
        }
        return new Result(false, items, response.header("ETag"), response.header("Last-Modified"));
    }

    @PreDestroy
    void close() throws IOException {
        http.close();
    }

    /** null si l'entrée n'a ni adresse http(s) ni titre. */
    private static Item item(SyndEntry entry, URI feedUri) {
        String link = entry.getLink() != null ? entry.getLink() : entry.getUri();
        String url = ArticleText.absoluteHttpUrl(feedUri, link);
        String title = ArticleText.title(ArticleText.text(entry.getTitle()));
        if (url == null || title.isEmpty()) {
            return null;
        }
        String content = entry.getContents().stream().map(SyndContent::getValue)
                .map(ArticleText::text).filter(text -> !text.isEmpty()).findFirst().orElse("");
        String description = entry.getDescription() == null ? "" : ArticleText.text(entry.getDescription().getValue());
        String summary = ArticleText.summary(description.isEmpty() ? content : description);
        String author = entry.getAuthor() == null || entry.getAuthor().isBlank() ? null : entry.getAuthor().strip();
        if (author != null && author.length() > 200) {
            author = author.substring(0, 200);
        }
        Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        return new Item(url, title, summary, content, author, date == null ? null : date.toInstant(),
                image(entry, feedUri));
    }

    /** Image de l'entrée : pièce jointe image, puis media:content ou media:thumbnail (Media RSS). */
    private static String image(SyndEntry entry, URI feedUri) {
        for (SyndEnclosure enclosure : entry.getEnclosures()) {
            if (enclosure.getType() != null && enclosure.getType().startsWith("image/")) {
                String url = ArticleText.absoluteHttpUrl(feedUri, enclosure.getUrl());
                if (url != null) {
                    return url;
                }
            }
        }
        for (Element element : entry.getForeignMarkup()) {
            if (!MEDIA_RSS.equals(element.getNamespaceURI())) {
                continue;
            }
            List<Element> candidates = new ArrayList<>();
            candidates.add(element);
            // media:group regroupe des media:content
            candidates.addAll(element.getChildren());
            for (Element candidate : candidates) {
                String name = candidate.getName();
                String type = candidate.getAttributeValue("type");
                String medium = candidate.getAttributeValue("medium");
                boolean isImage = name.equals("thumbnail") || name.equals("content") && looksLikeImage(candidate, type, medium);
                if (isImage) {
                    String url = ArticleText.absoluteHttpUrl(feedUri, candidate.getAttributeValue("url"));
                    if (url != null) {
                        return url;
                    }
                }
            }
        }
        return null;
    }

    /**
     * medium et type sont facultatifs en Media RSS : sans eux, un media:content avec des dimensions ou une extension
     * d'image est une image. Ceux qui se disent vidéo ou audio sont écartés.
     */
    private static boolean looksLikeImage(Element content, String type, String medium) {
        if (medium != null || type != null) {
            return "image".equals(medium) || type != null && type.startsWith("image/");
        }
        String url = content.getAttributeValue("url", "").toLowerCase();
        return content.getAttribute("width") != null || content.getAttribute("height") != null
                || url.matches(".*\\.(jpe?g|png|webp|gif|avif)(\\?.*)?$");
    }

    static String message(SafeHttp.Failure failure) {
        return switch (failure) {
            case NOT_ALLOWED -> "Feed address is not allowed";
            case UNREACHABLE -> "Feed is unreachable";
            case TOO_MANY_REDIRECTS -> "Feed redirects too many times";
            case TOO_LARGE -> "Feed is larger than 5 MB";
        };
    }
}
