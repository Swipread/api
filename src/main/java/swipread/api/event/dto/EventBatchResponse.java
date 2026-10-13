package swipread.api.event.dto;

/**
 * accepted : événements nouveaux, écrits et publiés ; duplicates : eventId déjà reçus, ignorés.
 */
public record EventBatchResponse(int accepted, int duplicates) {
}
