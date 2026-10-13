package swipread.api.event;

import java.util.Locale;

public enum EventType {
    IMPRESSION, VIEW, OPEN, READ, LIKE, UNLIKE, SAVE, UNSAVE, SHARE, HIDE;

    public static EventType of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
