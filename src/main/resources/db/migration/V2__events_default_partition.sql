-- Filet de sécurité : les événements hors des partitions mensuelles existantes atterrissent ici
-- au lieu de faire échouer l'insertion. EventPartitionJob crée les partitions à l'avance.
CREATE TABLE app.events_default PARTITION OF app.events DEFAULT;
