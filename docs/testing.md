# Tests

## Tests automatisés

```bash
./mvnw test
```

Docker doit tourner : les tests d'intégration démarrent leurs propres conteneurs `pgvector/pgvector:pg17` et `redis:7.4` via Testcontainers. La suite complète prend moins d'une minute.

### Organisation

| Type | Classes | Ce qui est couvert |
| --- | --- | --- |
| Unitaires | `FeedServiceTest`, `FeedRefillerTest` | Pagination, état aimé/sauvegardé des articles, seuil de remplissage, verrou, stock vide, complément par le repli, filtrage des articles déjà vus, invalidation, lot écarté puis recalculé une seule fois si le stock change de version pendant le calcul |
| Unitaires | `EventServiceTest`, `EventRulesTest` | Dédoublonnage, tri chronologique, enrichissement `articleWordCount`, événements forts (dont la règle de durée de `read`), validation des payloads, effet sur `user_articles` |
| Unitaires | `HttpRecommendationClientTest` | Requête au format du contrat, statuts 400/404/503, timeout réel à 300 ms, service injoignable (vrai serveur HTTP local) |
| Unitaires | `IdGeneratorTest` | Format et tri des ULID |
| Unitaires | `SlugsTest` | Slug d'un thème : accents, casse, ponctuation |
| Unitaires | `RomeFeedProbeTest` | Lecture RSS et Atom, flux vide, HTML, HTTP 404, hôte injoignable, refus des entités externes, redirections suivies et contrôlées à chaque saut, boucle de redirections, flux local refusé par défaut (serveur HTTP local) ; connexion par le résolveur de la politique (nom inventé), adresse interne refusée à la connexion (rebinding), hôte inconnu injoignable |
| Unitaires | `FeedAddressPolicyTest` | Protection SSRF : plages IPv4 et IPv6 internes refusées (dont IPv4 encapsulée et NAT64), adresses publiques acceptées, ports 80 et 443 seulement, schémas `http`/`https`, résolution refusée si une seule adresse est interne, hôte inconnu distinct d'une adresse interdite |
| Intégration | `AuthIntegrationTest` | Inscription, connexion, 401/409/400 en ProblemDetail, messages de validation en anglais avec leur `code` malgré `Accept-Language: fr` |
| Intégration | `RefreshTokenIntegrationTest` | Cookie HttpOnly à la connexion, access token de 15 min, rotation, grâce de 10 s sans nouveau cookie, réutilisation qui révoque la famille, token absent, inconnu ou expiré, logout, suppression de compte, CORS avec credentials, stockage haché |
| Intégration | `OnboardingIntegrationTest` | Thèmes, remplacement des choix, minimum de 3 thèmes, `/v1/me` |
| Intégration | `CorsIntegrationTest`, `RoleIntegrationTest` | Preflight CORS sans token, origine inconnue refusée ; rôle `READER`/`ADMIN`, 403 sur `/v1/admin/**`, promotion effective au token suivant |
| Intégration | `FeedIntegrationTest` | Première page de repli, remplissage asynchrone à 50, clés Redis, aucun doublon entre les pages, stock vidé par un changement de thèmes (seen conservé, rien vidé sur un 400) |
| Intégration | `FallbackFeedProviderIntegrationTest` | `seenAmong` (`SMISMEMBER`), articles récents hors `seen` sur plusieurs lots, listes `fallback:{slug}` hors `seen` et hors exclus |
| Intégration | `FeedStoreIntegrationTest` | Version du stock : ajout à la bonne version, lot écarté après une invalidation ou un changement de thèmes, suppression de compte qui efface aussi la version |
| Intégration | `OutboxIntegrationTest` | Outbox : messages validés publiés dans l'ordre puis supprimés, rien de publié pour une transaction annulée, `enqueue` hors transaction refusé |
| Intégration | `MockEventsConsumerIntegrationTest` | Mock de reco : message publié lu et acquitté par le groupe `mock-reco`, démarrage avec un groupe existant, consommateur retiré du groupe à l'arrêt, message illisible seulement loggé |
| Intégration | `RecoRoleIntegrationTest` | Rôle `swipread_reco` (V5), joué avec `SET ROLE` : sans connexion, lecture de `app` mais pas de l'email ni du hash de `app.users`, aucune écriture dans `app`, écriture et création de tables dans `reco`, tables ajoutées plus tard à `app` lisibles |
| Intégration | `EventIntegrationTest` | Écriture, agrégation, publication sur le stream, dédoublonnage, invalidation (`LTRIM` à 10 puis remplissage), partitions |
| Intégration | `SavedArticlesIntegrationTest` | Pagination par curseur, exclusion des articles masqués, `unsave`, thèmes triés par confiance, `liked` |
| Intégration | `AccountIntegrationTest` | Changement de nom, suppression de compte (base, Redis, message `user_deleted`), token refusé ensuite, email réutilisable |
| Intégration | `AdminTopicsIntegrationTest` | Création (slug, 409 sur slug identique), compteurs d'articles et de lecteurs, renommage sans changer le slug, 403 pour un lecteur |
| Intégration | `AdminSourcesIntegrationTest` | Création avec flux vérifié (serveur HTTP local), 409 sur `feedUrl` déjà utilisé, 422 si flux vide ou en erreur, désactivation, suppression (409 si la source a des articles), 403 pour un lecteur |
| Intégration | `HistoryIntegrationTest` | Articles ouverts seulement, tri par dernière ouverture, événement en retard, exclusion des masqués, curseur |
| Intégration | `UserArticleStatesIntegrationTest` | Lecture groupée de l'état aimé/sauvegardé |
| Intégration | `OpenApiIntegrationTest` | Spec publique et complète, réponses JSON, erreurs 4xx en `ProblemDetail` |

### Écrire un test d'intégration

Hériter de `swipread.api.IntegrationTest`, qui fournit :

- `mvc` (MockMvc) et `json` (JsonMapper) ;
- `registerUser()`, qui renvoie un utilisateur avec son token (`user.bearer()`).

Toutes les classes partagent la même configuration, ce qui permet à Spring de réutiliser un seul contexte et un seul jeu de conteneurs. N'ajoute donc pas de `@MockitoBean` ou de propriété spécifique sans raison : cela recréerait un contexte, avec de nouveaux conteneurs.

Les données ne sont pas nettoyées entre les tests. Chaque test crée son propre utilisateur (email aléatoire) et `TestArticles.seed(jdbc, n)` insère des articles de façon idempotente. Pour attendre le remplissage asynchrone du stock, utiliser Awaitility (`await().until(...)`).

`TestApiApplication` lance l'application avec les conteneurs de test, sans Docker Compose. C'est pratique pour un essai rapide depuis l'IDE.

## Scénario de bout en bout (Bruno / Postman)

Le même scénario existe en deux versions, à choisir selon l'outil du testeur. Il déroule tout le parcours avec des assertions : 47 requêtes et 52 tests. Le dossier « 08 Account » supprime le compte de test ; « 09 Admin » se connecte avec le compte admin du seed et crée une seule fois un thème « Thème de test » dans la base de dev. Il ne crée pas de source (il faudrait un vrai flux en ligne) : cette création est couverte par les tests d'intégration.

| Dossier | Ce qui est vérifié |
| --- | --- |
| 01 Auth | Inscription, email déjà pris (409), corps invalide (400 avec le détail par champ), connexion, mauvais mot de passe (401) ; codes de contrainte (`Email`, `Size`, `NotBlank`) et message en anglais malgré `Accept-Language: fr` ; cookie de refresh HttpOnly, renouvellement avec rotation, déconnexion, renouvellement refusé après déconnexion (401) |
| 02 Onboarding | Liste des thèmes (au moins six), choix de trois thèmes, thème inconnu (400), `/v1/me` |
| 03 Feed | Première page servie par le repli, puis page de la reco 1,5 s plus tard, sans article déjà servi ; changement de thèmes, puis page servie par le repli (stock vidé) |
| 04 Events | Lot `view` + `save` accepté, même lot renvoyé et dédoublonné, payload invalide, type inconnu |
| 05 Saved | L'article sauvegardé apparaît, curseur invalide (400) |
| 06 Security and docs | 401 sans token et avec un token invalide, spec OpenAPI |

Il suppose l'API lancée en profil `dev`, avec le mock de reco : les tests du feed attendent `reason: "explore"`. Chaque exécution crée un nouvel utilisateur (`smoke+<timestamp>@example.org`) et peut donc être relancée à volonté.

> Toute modification du scénario doit être reportée dans les deux versions.

### Bruno — [bruno/](bruno/)

- Dans l'application Bruno : *Open Collection* → `docs/bruno`, choisir l'environnement `dev`, puis *Run* sur la collection.
- En ligne de commande (`npm i -g @usebruno/cli`) :

```bash
cd docs/bruno
bru run --env dev
# autre port : bru run --env dev --env-var baseUrl=http://localhost:8089
```

### Postman — [postman/](postman/)

- Dans Postman : *Import* → `swipread.postman_collection.json` et `swipread-dev.postman_environment.json`, sélectionner l'environnement *Swipread dev*, puis *Run collection*. Les requêtes doivent être lancées dans l'ordre de la collection.
- En ligne de commande avec Newman :

```bash
npx newman run docs/postman/swipread.postman_collection.json \
  -e docs/postman/swipread-dev.postman_environment.json
# autre port : ajouter --env-var baseUrl=http://localhost:8089
```
