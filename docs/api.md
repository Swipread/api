# Swipread API — liste des endpoints (v1)

Référence des échanges front ↔ backend. Les formats partagés avec la reco (recommandations, stream `events`, clés Redis) sont dans [Contrat backend reco.md](Contrat%20backend%20reco.md).

- Base URL en dev : `http://localhost:8080`
- Spec OpenAPI : `GET /v3/api-docs` — Swagger UI : `/swagger-ui.html`
- Scénarios de test exécutables (Bruno, Postman) : [Tests](testing.md#scénario-de-bout-en-bout-bruno--postman)
- Fonctionnement interne du feed et des événements : [Architecture](architecture.md)
- Retour au [sommaire](README.md)

## Conventions

- JSON en camelCase, dates ISO 8601 UTC, IDs préfixés (`u_` utilisateur, `a_` article, `r_` lot de reco, `e_` événement).
- Authentification : en-tête `Authorization: Bearer <accessToken>` sur tous les endpoints sauf `register`, `login`, la doc OpenAPI et `/actuator/health`.
- Le `userId` n'est jamais envoyé par le front : il est lu dans le token.
- CORS : les origines autorisées sont configurées par environnement (`swipread.cors.allowed-origins`, `http://localhost:5173` en dev). Méthodes `GET, POST, PUT, PATCH, DELETE` ; en-têtes `Authorization, Content-Type, X-Session-Id, X-Device`. Les requêtes préalables `OPTIONS` passent sans token.
- Les réponses sont en `application/json`.
- Erreurs au format ProblemDetail (RFC 9457), `Content-Type: application/problem+json` :

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/v1/auth/register",
  "errors": [{ "field": "email", "code": "Email", "message": "must be a well-formed email address" }]
}
```

Le schéma `ProblemDetail` est déclaré dans la spec OpenAPI pour toutes les réponses 4xx : 400 sur tout endpoint qui reçoit des paramètres, 401 sur tout endpoint authentifié, et les autres codes (409…) là où ils peuvent se produire.

`errors` n'est présent que pour les erreurs de validation. `code` est le nom de la contrainte non respectée (`NotBlank`, `NotNull`, `Size`, `Email`, `Pattern`) : c'est sur `field` et `code` que les clients doivent s'appuyer pour afficher leur propre message. `message` est toujours en anglais, quels que soient l'en-tête `Accept-Language` et la langue du serveur.

## Récapitulatif

| Méthode | Chemin | Auth | Rôle |
| --- | --- | --- | --- |
| POST | `/v1/auth/register` | non | Créer un compte, renvoie un token |
| POST | `/v1/auth/login` | non | Obtenir un token |
| POST | `/v1/auth/refresh` | cookie | Nouvel access token (rotation du refresh token) |
| POST | `/v1/auth/logout` | cookie | Révoquer le refresh token |
| GET | `/v1/me` | oui | Profil et thèmes choisis |
| PATCH | `/v1/me` | oui | Modifier le profil (`displayName`) |
| DELETE | `/v1/me` | oui | Supprimer le compte et les données personnelles |
| GET | `/v1/topics` | oui | Thèmes proposés à l'onboarding |
| PUT | `/v1/me/topics` | oui | Remplacer les thèmes choisis |
| GET | `/v1/feed` | oui | Page suivante du feed (10 articles) |
| POST | `/v1/events` | oui | Envoyer un lot d'événements |
| GET | `/v1/me/saved` | oui | Articles sauvegardés, paginés |
| GET | `/v1/me/history` | oui | Historique de lecture (articles ouverts), paginé |
| GET | `/v1/admin/topics` | admin | Thèmes avec nombre d'articles et de lecteurs |
| POST | `/v1/admin/topics` | admin | Créer un thème |
| PATCH | `/v1/admin/topics/{id}` | admin | Renommer un thème |
| GET | `/v1/admin/sources` | admin | Sources ingérées |
| POST | `/v1/admin/sources` | admin | Ajouter une source (flux vérifié) |
| PATCH | `/v1/admin/sources/{id}` | admin | Activer ou désactiver une source |
| DELETE | `/v1/admin/sources/{id}` | admin | Supprimer une source sans article |

## Auth

### POST /v1/auth/register

```json
{ "email": "alice@example.org", "password": "password123", "displayName": "Alice", "locale": "fr-FR" }
```

| Champ | Règle |
| --- | --- |
| email | requis, email valide, ≤ 254 caractères, normalisé en minuscules |
| password | requis, 8 à 72 caractères |
| displayName | requis, ≤ 50 caractères |
| locale | optionnel, forme `fr-FR`, défaut `fr-FR` |

Réponse **201** :

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 900, "userId": "u_01M4T6FKYMAGRVXAG4VNE8DQ5W" }
```

`expiresIn` est la durée de validité de l'access token, **en secondes** (15 min). La réponse pose aussi le cookie du refresh token :

```
Set-Cookie: swipread_refresh=…; Path=/v1/auth; Max-Age=2592000; HttpOnly; SameSite=Strict; Secure
```

`Secure` est absent en dev et en test (HTTP). Le cookie n'est jamais lisible en JavaScript ; le navigateur l'envoie seul aux routes `/v1/auth`, à condition que le front appelle avec `credentials: "include"`.

Erreurs : 400 validation, 409 email déjà utilisé.

### POST /v1/auth/login

```json
{ "email": "alice@example.org", "password": "password123" }
```

Réponse **200** : même format que `register`. Erreurs : 400 validation, 401 identifiants invalides (même réponse que l'email existe ou non).

L'access token est un JWT HS256 (`sub` = userId, claim `roles`), valable 15 min. Il est renouvelé par le refresh token, sans redemander le mot de passe.

### POST /v1/auth/refresh

Sans corps ni en-tête `Authorization` : seulement le cookie `swipread_refresh` (`credentials: "include"`). Réponse **200** au format de `login` :

- **Rotation.** Le cookie est remplacé par un nouveau refresh token, valable de nouveau 30 jours (durée glissante). L'ancien ne sert plus.
- **Onglets simultanés.** Un token remplacé depuis moins de 10 s reçoit encore un access token, sans nouveau cookie (le navigateur a déjà le nouveau).
- **Réutilisation.** Un token remplacé présenté plus tard est considéré comme volé : toute la session de cet appareil est révoquée, y compris le token le plus récent.
- Le rôle est relu en base : une promotion `ADMIN` prend effet au renouvellement suivant.

Erreur **401** `"Invalid refresh token"` (cookie absent, inconnu, expiré, révoqué ou réutilisé), avec effacement du cookie : le front doit alors renvoyer vers la connexion.

### POST /v1/auth/logout

Avec le cookie, sans `Authorization`. Révoque la session de l'appareil (la famille du refresh token) et efface le cookie. Réponse **204**, même sans cookie. L'access token en cours reste valable jusqu'à son expiration : le front doit l'oublier.

## Profil et onboarding

### GET /v1/me

Réponse **200** :

```json
{
  "id": "u_01M4T6FKYMAGRVXAG4VNE8DQ5W",
  "email": "alice@example.org",
  "displayName": "Alice",
  "locale": "fr-FR",
  "createdAt": "2026-10-13T08:36:21.512Z",
  "role": "READER",
  "topics": [{ "id": 1, "slug": "tech", "label": "Tech" }]
}
```

`role` vaut `READER` ou `ADMIN`. Seul `ADMIN` donne accès aux routes `/v1/admin/**` ; les autres reçoivent **403**. Le rôle est porté par le token : une promotion ne prend effet qu'à la connexion suivante.

### PATCH /v1/me

```json
{ "displayName": "Alice M." }
```

`displayName` : requis, non vide, ≤ 50 caractères, espaces de début et de fin retirés. Réponse **200** : le profil complet, au format de `GET /v1/me`. Erreur 400 si `displayName` est invalide.

### DELETE /v1/me

Réponse **204** sans corps. Sont supprimés :

- le compte, ses thèmes et son état par article (sauvegardés, likes, historique) ;
- tous ses événements de `app.events` ;
- ses clés Redis (`feed:{userId}*`, `seen:{userId}`).

La reco est prévenue par un message `user_deleted` sur le stream `events` et efface ses propres données. Le token du compte est ensuite refusé (**401**) : le front doit l'oublier et revenir à l'écran de connexion. L'email peut resservir pour une nouvelle inscription.

### GET /v1/topics

Réponse **200**, triée par libellé :

```json
[{ "id": 4, "slug": "economy", "label": "Économie" }, { "id": 1, "slug": "tech", "label": "Tech" }]
```

### PUT /v1/me/topics

Remplace l'ensemble des thèmes de l'utilisateur.

```json
{ "topicIds": [1, 2, 3] }
```

`topicIds` : 3 à 50 IDs (US3). Réponse **200** : la liste des thèmes enregistrés (même format que `GET /v1/topics`). Erreur 400 si un ID est inconnu (`"detail": "Unknown topic ids: [9999]"`) ou s'il y a moins de 3 IDs (`errors[0].field` vaut `topicIds`).

Après l'enregistrement, le stock du feed, calculé sur les anciens thèmes, est vidé (US4). Le `GET /v1/feed` suivant sert sa première page par le repli sur les nouveaux thèmes, puis la reco reprend en arrière-plan. Les articles déjà servis restent exclus. Une requête refusée (400) ne vide rien.

## Feed

### GET /v1/feed

| En-tête optionnel | Rôle |
| --- | --- |
| `X-Session-Id` | Session en cours, transmise à la reco (`context.sessionId`) |
| `X-Device` | `mobile` ou `desktop`, transmis à la reco |

Réponse **200** :

```json
{
  "items": [
    {
      "articleId": "a_387",
      "title": "…",
      "summary": "…",
      "url": "https://…",
      "imageUrl": "https://…",
      "author": "…",
      "sourceName": "Le Monde",
      "language": "fr",
      "wordCount": 850,
      "publishedAt": "2026-10-12T08:00:00Z",
      "topics": [{ "id": 1, "slug": "tech", "label": "Tech" }],
      "liked": false,
      "saved": false,
      "requestId": "r_7c21",
      "reason": "similar",
      "position": 0
    }
  ]
}
```

- Jusqu'à 10 articles par appel ; chaque appel consomme la page (pas de retour en arrière).
- `requestId` et `reason` sont à recopier dans les événements de l'article. Pour un article servi par le feed de repli, les deux valent `fallback`.
- `position` est l'index dans la page (à partir de 0).
- `topics` : thèmes détectés pour l'article, du plus sûr au moins sûr ; liste vide si l'ingestion n'en a détecté aucun. Pour une pastille unique, prendre le premier.
- `liked` et `saved` : état de l'article pour l'utilisateur courant, toujours présents.
- Les champs `null` sont omis (`imageUrl`, `author` notamment).
- Premier appel d'un nouvel utilisateur : la page vient du feed de repli ; les suivantes viennent de la reco une fois le stock rempli en arrière-plan.

## Événements

### POST /v1/events

Envoi toutes les 5 secondes, dès 20 événements, ou à la fermeture de l'app.

```json
{
  "sessionId": "s_8f3a",
  "events": [
    {
      "eventId": "e_01J9X",
      "type": "view",
      "articleId": "a_387",
      "requestId": "r_7c21",
      "position": 3,
      "occurredAt": "2026-10-12T10:41:12Z",
      "payload": { "dwellMs": 4200 }
    }
  ]
}
```

| Champ | Règle |
| --- | --- |
| sessionId | requis, ≤ 64 caractères |
| events | 1 à 100 événements |
| eventId | requis, généré par le front (UUID ou ULID), sert au dédoublonnage |
| type | `impression`, `view`, `open`, `read`, `like`, `unlike`, `save`, `unsave`, `share`, `hide` |
| articleId | requis |
| requestId | optionnel, recopié depuis le feed |
| position | optionnel, ≥ 0 |
| occurredAt | requis, pas plus d'un jour dans le futur |

| type | payload |
| --- | --- |
| view | `dwellMs` requis (0 à 86 400 000) |
| read | `readMs` requis (0 à 86 400 000), `scrollDepth` optionnel (0 à 1) |
| share | `channel` requis |
| hide | `reason` optionnel (texte) |
| autres | aucun |

Réponse **202** :

```json
{ "accepted": 1, "duplicates": 0 }
```

- Un `eventId` déjà reçu (même `occurredAt`) est ignoré et compté dans `duplicates` : renvoyer un lot après une erreur réseau est sans risque.
- Un payload invalide rejette tout le lot en 400 (`"detail": "Event e_01J9X (read): scrollDepth must be between 0 and 1"`).
- `like`, `save`, `share`, `hide` et un `read` complet : `scrollDepth > 0.8`, ou `readMs` ≥ 60 % du temps de lecture estimé (`wordCount` / 230 mots par minute) renouvellent le stock du feed (les 10 prochains articles sont conservés). Exemple : pour un article de 850 mots (3 min 42 s estimées), un `read` est fort à partir de `readMs` = 133 044.
- `unsave` retire l'article des sauvegardés, sur le modèle de `unlike`. Ni `unlike` ni `unsave` ne renouvellent le stock.

## Articles sauvegardés

### GET /v1/me/saved

| Paramètre | Règle |
| --- | --- |
| limit | 1 à 100, défaut 20 |
| cursor | optionnel, valeur `nextCursor` de la page précédente |

Réponse **200**, du plus récemment sauvegardé au plus ancien, articles masqués (`hide`) exclus :

```json
{
  "items": [{ "article": { "id": "a_387", "title": "…", "summary": "…", "url": "https://…", "sourceName": "…", "language": "fr", "wordCount": 850, "publishedAt": "2026-10-12T08:00:00Z", "topics": [{ "id": 1, "slug": "tech", "label": "Tech" }] }, "savedAt": "2026-10-12T10:41:12Z", "liked": true }],
  "nextCursor": "MjAyNi0xMC0xMlQxMDo0MToxMlp8YV8zODc"
}
```

`article` a la même forme que les champs d'article de `GET /v1/feed`, avec `id` au lieu de `articleId`. `liked` indique si l'article est aussi aimé.

`nextCursor` est absent sur la dernière page. Erreur 400 si le curseur est invalide. Pour retirer un article des sauvegardés, envoyer un événement `unsave`.

### GET /v1/me/history

Mêmes paramètres que `GET /v1/me/saved` (`limit` de 1 à 100, défaut 20 ; `cursor`).

Réponse **200**, de l'article ouvert le plus récemment au plus ancien :

```json
{
  "items": [{ "article": { "id": "a_387", "title": "…", "topics": [{ "id": 1, "slug": "tech", "label": "Tech" }], "…": "…" }, "readAt": "2026-10-12T10:41:12Z", "liked": false, "saved": true }],
  "nextCursor": "MjAyNi0xMC0xMlQxMDo0MToxMlp8YV8zODc"
}
```

- Un article entre dans l'historique dès un événement `open` ou `read`. Les cartes seulement affichées ou défilées (`impression`, `view`) n'y figurent pas.
- `readAt` est la date de la **dernière** ouverture : un article rouvert remonte en tête, sans doublon.
- Les articles masqués (`hide`) sont exclus.
- Le regroupement « Aujourd'hui » / « Cette semaine » est laissé au front, à partir de `readAt`.

## Administration

Réservé au rôle `ADMIN` (champ `role` de `GET /v1/me`). Un lecteur reçoit **403**.

### GET /v1/admin/topics

Réponse **200**, triée par libellé :

```json
[{ "id": 1, "slug": "tech", "label": "Tech", "articleCount": 128, "readerCount": 42 }]
```

- `articleCount` : articles associés au thème par l'ingestion (colonne « Articles » du wireframe).
- `readerCount` : lecteurs qui ont choisi ce thème (colonne « Lecteurs intéressés »).

### POST /v1/admin/topics

```json
{ "label": "Jeux vidéo" }
```

`label` : requis, ≤ 50 caractères. Le `slug` est tiré du libellé (minuscules, accents retirés, tirets) : `jeux-video`. Réponse **201** au format de `GET /v1/admin/topics`, compteurs à 0. Le thème est aussitôt proposé à l'onboarding.

Erreurs : **409** si un thème a déjà ce slug (`"Jeux Video"` et `"jeux vidéo"` donnent le même), **400** si le libellé est vide ou ne contient aucune lettre ni chiffre.

### PATCH /v1/admin/topics/{id}

```json
{ "label": "Jeux" }
```

Change le libellé seulement : le `slug` reste celui de la création, car la reco l'utilise dans les clés `fallback:{slug}`. Réponse **200** au format de `GET /v1/admin/topics`. Erreur **404** si le thème n'existe pas.

### GET /v1/admin/sources

Réponse **200**, triée par nom :

```json
[{ "id": 3, "name": "Le Monde", "siteUrl": "https://www.lemonde.fr", "feedUrl": "https://www.lemonde.fr/rss/une.xml", "language": "fr", "license": "Usage interne", "active": true }]
```

`license` est omis s'il n'est pas renseigné.

### POST /v1/admin/sources

```json
{ "name": "Le Monde", "siteUrl": "https://www.lemonde.fr", "feedUrl": "https://www.lemonde.fr/rss/une.xml", "language": "fr", "license": "Usage interne" }
```

| Champ | Règle |
| --- | --- |
| name | requis, ≤ 100 caractères |
| siteUrl, feedUrl | requis, URL `http` ou `https`, ≤ 2048 caractères |
| language | requis, code ISO 639-1 en minuscules (`fr`, `en`…) |
| license | optionnel, ≤ 200 caractères |

Avant l'enregistrement, l'API télécharge `feedUrl` (5 s pour la connexion, 10 s pour la réponse, 5 Mo au plus, 5 redirections au plus) et le lit comme un flux RSS ou Atom. Réponse **201** au format de `GET /v1/admin/sources`, `active: true`.

Protection SSRF : seuls les flux publics sont téléchargés, en `http` ou `https` sur les ports 80 et 443. Une adresse de bouclage, privée (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7), de lien local (dont les métadonnées cloud 169.254.169.254) ou de multicast est refusée, y compris comme destination d'une redirection. Le contrôle a lieu au moment même de la connexion : un domaine qui changerait d'adresse entre-temps (DNS rebinding) est refusé aussi.

Erreurs :

- **409** si une source utilise déjà ce `feedUrl` ;
- **422** si le flux est injoignable (`"Feed is unreachable"`), répond une erreur HTTP (`"Feed answered HTTP 404"`), n'est pas un flux valide (`"Not a valid RSS or Atom feed"`), ne contient aucun article (`"Feed has no articles"`), vise une adresse non autorisée (`"Feed address is not allowed"`), redirige plus de 5 fois (`"Feed redirects too many times"`) ou dépasse 5 Mo (`"Feed is larger than 5 MB"`). Le `detail` peut être affiché tel quel à l'administrateur.

### PATCH /v1/admin/sources/{id}

```json
{ "active": false }
```

`active` est requis, c'est le seul champ modifiable. Une source inactive est ignorée par l'ingestion ; ses articles déjà publiés restent dans les feeds. Réponse **200** au format de `GET /v1/admin/sources`. Erreur **404** si la source n'existe pas.

### DELETE /v1/admin/sources/{id}

Réponse **204**. Erreur **409** si la source a déjà des articles : il faut alors la désactiver, car supprimer ses articles effacerait les sauvegardés et l'historique des lecteurs. Erreur **404** si la source n'existe pas.
