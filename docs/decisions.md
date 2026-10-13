# Décisions et points ouverts

Le [contrat backend / reco](Contrat%20backend%20reco.md) fait foi. Cette page liste ce que l'implémentation a dû décider là où le contrat était muet ou ambigu, avec le statut de chaque point.

## Écarts au contrat validés

Validés le 13/10/2026, **reportés dans le contrat le 10/11/2026 (v1.1)**.

| Sujet | Décision | Raison |
| --- | --- | --- |
| Origine des articles du stock | Nouvelle clé `feed:{userId}:meta` (Hash `articleId → requestId\|reason`, TTL 6 h). `feed:{userId}` reste une liste d'IDs purs. | Le stock mélange plusieurs lots de reco et des articles de repli. `feed:{userId}:req` ne garde que le dernier `requestId`, ce qui fausserait l'attribution dans les événements. |
| Stock vide | La première page est servie directement par le repli, puis le remplissage par la reco est lancé en arrière-plan. | « L'utilisateur n'attend jamais la reco. » |
| Rôle des utilisateurs (demande du front) | Colonne `app.users.role` (`READER` par défaut, ou `ADMIN`), migration V3. Le rôle est mis dans le claim `roles` du JWT et protège `/v1/admin/**`. | Le front doit afficher et protéger l'administration (US17, US18). Lire le rôle dans le token évite une requête en base par appel ; en contrepartie, une promotion ne prend effet qu'à la connexion suivante. |
| Retrait des sauvegardés (demande du front) | Nouveau type d'événement `unsave`, sur le modèle de `unlike` : `saved_at` repasse à `NULL`. Il est publié sur le stream comme les autres types et n'invalide pas le stock. | `app.events` reste la source de vérité et la reco reçoit le signal. **La reco doit accepter ce type.** |
| `read` sans `scrollDepth` (demande du front) | `scrollDepth` devient optionnel. Un `read` est fort si `scrollDepth > 0.8` **ou** si `readMs` ≥ 60 % du temps de lecture estimé, `wordCount` / 230 mots par minute. | Sur le web, l'article s'ouvre sur le site de la source : le front ne peut pas mesurer le défilement, et la règle du contrat ne se déclencherait jamais. |
| Historique de lecture (demande du front) | Colonne `app.user_articles.opened_at` (migration V4, reprise depuis `app.events`), mise à jour par `open` et `read` avec la date la plus récente. `GET /v1/me/history` liste les articles ouverts, hors articles masqués. | Le contrat prévoit l'écran « historique » sur `user_articles`, mais aucune colonne ne distingue un article ouvert d'une carte seulement défilée. |
| Suppression de compte (demande du front) | `DELETE /v1/me` supprime le compte (cascade sur `user_topics` et `user_articles`), ses lignes de `app.events` et ses clés Redis, puis publie sur le stream `events` un message `{"version": 1, "eventId": "e_…", "userId": "u_…", "type": "user_deleted", "occurredAt": …, "receivedAt": …, "payload": {}}`. Le token d'un compte supprimé est refusé : le décodeur JWT vérifie l'existence du compte à chaque requête (lecture par clé primaire). | Effacement des données personnelles. Le backend n'écrit pas dans le schéma `reco` : c'est la reco qui efface `reco.user_profiles` à réception du message. **Nouveau type de message pour la reco.** Sans le contrôle du token, un client resté connecté pourrait réécrire des événements pendant les 24 h de validité du token. |
| Version du stock (10/11/2026, contrat v1.2) | Nouvelle clé `feed:{userId}:ver` (String, 6 h), incrémentée avant toute invalidation ou vidage du stock. Le lot d'un remplissage n'est ajouté que si la version n'a pas changé depuis son lancement (script Lua), sinon il est recalculé une fois. La suppression de compte efface aussi cette clé. | Un remplissage lancé avant un événement fort ou un changement de thèmes ajoutait ensuite un lot calculé sans ce signal. Pour la suppression de compte, l'effacement complet des clés prime : un remplissage en cours peut encore recréer un stock, que plus personne ne lit et qui expire en 6 h. |
| Outbox du stream (10/11/2026, contrat v1.3) | Table `app.event_outbox` (V6), remplie dans la transaction des événements et de la suppression de compte ; `OutboxRelay` publie toutes les 200 ms, par lots de 100, puis supprime les lignes. | Plus de message publié pour une transaction annulée, et une panne de Redis ne fait plus échouer `POST /v1/events`. En contrepartie, la publication est différée de 200 ms au plus en temps normal, et l'ordre n'est garanti qu'à l'intérieur d'un lot quand plusieurs instances relaient. |
| Refresh token (10/11/2026, contrat v1.4) | Access token de 15 min ; refresh token opaque dans un cookie `swipread_refresh` (HttpOnly, SameSite=Strict, `Path=/v1/auth`, Secure hors dev et test), stocké haché dans `app.refresh_tokens` (V7), valable 30 jours glissants. Rotation à chaque `POST /v1/auth/refresh` ; réutilisation d'un token remplacé = révocation de la famille, sauf dans les 10 s qui suivent (onglets simultanés). `POST /v1/auth/logout` révoque la famille. | Fin de la reconnexion quotidienne, sans exposer de token durable au JavaScript (XSS). La grâce de 10 s évite de déconnecter un lecteur qui a deux onglets ouverts. |
| Vérification des flux (10/11/2026) | Protection SSRF : schémas `http`/`https`, ports 80 et 443, adresses internes refusées (IPv4 et IPv6), redirections suivies à la main et contrôlées à chaque saut. Client Apache HttpClient 5 dont le résolveur DNS est `FeedAddressPolicy` : l'adresse contrôlée est celle de la connexion. Pas de proxy système. | L'API télécharge une URL choisie par un administrateur. Avec le client HTTP de Java, qui refait sa propre résolution, un domaine pouvait changer d'adresse entre le contrôle et la connexion (DNS rebinding). |
| Partitions de `app.events` | Partition `app.events_default` (migration V2) et job qui crée chaque nuit la partition du mois suivant. | Le DDL ne crée que la partition d'octobre 2026 : sans cela, tout événement de novembre ferait échouer l'insertion. |
| Encodage du stream `events` | Un seul champ `data` qui contient le JSON du contrat. Rétention par `XADD … MINID ~ (now - 7 j)`. | Préserve le JSON imbriqué (`payload`) tel que décrit dans le contrat. Un stream Redis n'a pas de TTL. |

### À annoncer à l'équipe reco

Résumé dans l'historique du contrat (v1.1). Trois de ces écarts changent ce que la reco reçoit sur le stream `events` :

- nouveau type d'événement `unsave` (retrait des sauvegardés) ;
- `payload.scrollDepth` peut être absent d'un `read` ; `readMs` et `articleWordCount` restent fournis ;
- nouveau message `user_deleted` (sans `articleId` ni `sessionId`) : la reco doit effacer `reco.user_profiles` pour ce `userId`.

## Interprétations validées

Choix faits par défaut pendant l'implémentation, là où le contrat était muet ou ambigu. Tous ont été **validés tels quels le 10/11/2026** ; l'alternative écartée reste notée pour mémoire. Ceux qui touchent le contrat y sont reportés depuis la v1.1 (10/11/2026).

| # | Sujet | Choix validé | Alternative écartée |
| --- | --- | --- | --- |
| 1 | `reason` dans les événements : le format d'événement n'a pas de champ `reason` | Pour un article de repli, le feed renvoie `requestId = "fallback"`, que le front recopie | Ajouter `payload.reason`, ou un champ `reason` (changement de format) |
| 2 | Stock « d'au plus 50 » alors qu'on redemande 50 sous le seuil | `count = 50 - taille actuelle du stock` | Toujours demander 50 et tronquer |
| 3 | Articles servis mais pas encore présents dans l'historique en base | Le backend retire de la réponse de la reco les articles de `seen:{userId}` | Laisser la reco seule responsable de l'exclusion |
| 4 | Repli insuffisant : le contrat ne prévoit les articles récents que si `fallback:*` est vide | Complément systématique par les articles les plus récents | Servir un stock plus court |
| 5 | `unlike` est classé « Fort » dans le tableau des types, mais absent de la règle d'invalidation | Pas d'invalidation sur `unlike`, ni sur `unsave` | Les ajouter aux événements forts |
| 6 | Réponse 400 de la reco : « log, pas de retry » | Log en erreur et repli quand même, pour ne pas laisser un stock vide | Ne rien ajouter au stock |
| 7 | Contexte de la reco : `GET /v1/feed` ne reçoit ni `sessionId` ni `device` | En-têtes optionnels `X-Session-Id` et `X-Device`. `locale` vient du profil utilisateur | Paramètres de requête |
| 8 | `fallback:{topic}` : clé par slug ou par ID ? | Par slug (`fallback:tech`) | Par ID numérique |
| 9 | Dédoublonnage sur `eventId` alors que la clé primaire est `(event_id, occurred_at)` | Seuls les renvois identiques sont détectés | Index unique sur `event_id` seul (impossible sur une table partitionnée sans inclure la clé de partition) |
| 10 | Lot contenant un événement invalide | Tout le lot est rejeté en 400 | Accepter les événements valides et lister les rejetés |
| 11 | Format de la réponse de `GET /v1/feed`, non décrit | Liste plate (champs de l'article + `requestId`, `reason`, `position` dans la page, à partir de 0) | — |
| 12 | Temps de lecture | `readMs` n'est pas ajouté à `total_dwell_ms` (seul `dwellMs` de `view` l'est) | Additionner les deux |
| 13 | Renommer un thème (`PATCH /v1/admin/topics/{id}`) : que devient le slug ? | Le slug reste celui de la création, seul le libellé change | Recalculer le slug, au risque de casser les clés `fallback:{slug}` de la reco |
| 14 | Supprimer un thème : pas demandé par le front | Pas d'endpoint | `DELETE` seulement si aucun article ni lecteur ne l'utilise |
| 15 | `DELETE /v1/admin/sources/{id}` d'une source qui a des articles | **409**, il faut la désactiver | Supprimer aussi ses articles, ce qui efface sauvegardés et historique des lecteurs |
| 16 | `PATCH /v1/admin/sources/{id}` : champs modifiables | `active` seulement, comme demandé par le front | Nom, licence ; un changement de `feedUrl` demanderait une nouvelle vérification du flux |

## À confirmer

| # | Sujet | Choix actuel | Alternative |
| --- | --- | --- | --- |
| 17 | Rôle de la reco (V5) : le contrat dit « lecture seule sur `app` » sans détailler | Pas d'accès aux colonnes `email`, `password_hash` et `display_name` de `app.users` (seulement `id`, `locale`, `created_at`) ; la reco peut créer ses propres tables dans `reco` | Lecture de toutes les colonnes ; ou schéma `reco` entièrement géré par les migrations du backend |

## Manques du contrat

- **Changement de thèmes et reco.** Depuis le 10/11/2026, un `PUT /v1/me/topics` vide le stock du feed (US4, voir [architecture](architecture.md#changement-de-thèmes)). En revanche, le contrat ne dit pas si la reco doit être prévenue : aucun message n'est publié sur le stream `events`. À voir avec l'équipe reco (lecture de `app.user_topics` à chaque appel, ou nouveau message `topics_changed`).

## Dette technique connue

Aucune depuis le 10/11/2026 : les huit points relevés pendant l'implémentation sont traités (voir les écarts validés ci-dessus et l'historique du contrat). Ajouter ici toute nouvelle dette, avec son risque et une piste.
