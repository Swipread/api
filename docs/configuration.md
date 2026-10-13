# Configuration

Toute la configuration est dans `src/main/resources/application.yaml`, surchargée par `application-dev.yaml` et `application-test.yaml`. Toute propriété peut être redéfinie par une variable d'environnement, selon la convention Spring : `swipread.reco.base-url` devient `SWIPREAD_RECO_BASE_URL`.

## Propriétés de l'application

| Propriété | Défaut | Variable d'environnement | Rôle |
| --- | --- | --- | --- |
| `swipread.jwt.secret` | aucun (obligatoire) | `SWIPREAD_JWT_SECRET` | Clé HMAC de signature des JWT, au moins 32 caractères |
| `swipread.jwt.ttl` | `15m` | `SWIPREAD_JWT_TTL` | Durée de validité d'un access token |
| `swipread.auth.refresh-ttl` | `30d` | `SWIPREAD_AUTH_REFRESH_TTL` | Durée de vie d'un refresh token, repartie à chaque renouvellement |
| `swipread.auth.refresh-grace` | `10s` | `SWIPREAD_AUTH_REFRESH_GRACE` | Délai pendant lequel un refresh token tout juste remplacé reste accepté sans rotation (onglets simultanés) |
| `swipread.auth.refresh-cookie-secure` | `true` | `SWIPREAD_AUTH_REFRESH_COOKIE_SECURE` | Attribut `Secure` du cookie. `false` en dev et en test (HTTP) ; **toujours `true` en production** |
| `swipread.cors.allowed-origins` | vide | `SWIPREAD_CORS_ALLOWED_ORIGINS` | Origines du frontend autorisées à appeler l'API depuis un navigateur, séparées par des virgules. `http://localhost:5173` en dev et en test |
| `swipread.reco.base-url` | `http://localhost:8081` | `SWIPREAD_RECO_URL` | URL du service de reco (ignorée avec `mock-reco`) |
| `swipread.reco.timeout` | `300ms` | `SWIPREAD_RECO_TIMEOUT` | Délai de connexion et de réponse de la reco (fixé par le contrat) |
| `swipread.feed.page-size` | `10` | `SWIPREAD_FEED_PAGE_SIZE` | Articles par page, et articles conservés lors d'une invalidation |
| `swipread.feed.stock-size` | `50` | `SWIPREAD_FEED_STOCK_SIZE` | Taille maximale du stock Redis |
| `swipread.feed.refill-threshold` | `20` | `SWIPREAD_FEED_REFILL_THRESHOLD` | Un remplissage est lancé quand il reste moins d'articles que ce seuil |
| `swipread.sources.allow-local-feeds` | `false` | `SWIPREAD_SOURCES_ALLOW_LOCAL_FEEDS` | Lève la protection SSRF de la vérification des flux (adresses internes, ports autres que 80 et 443). `true` dans le profil `test` uniquement, pour les flux servis en local ; **jamais en production** |

Les valeurs du feed et le timeout viennent du contrat avec la reco. Il faut en discuter avec l'équipe reco avant de les changer.

Les durées de vie des clés Redis (6 h, 5 s, 30 j, 7 j) sont des constantes du contrat, dans `FeedStore` et `EventPublisher`.

## Réglages Spring notables

| Propriété | Valeur | Pourquoi |
| --- | --- | --- |
| `spring.datasource.hikari.connection-init-sql` | `SET TIME ZONE 'UTC'` | Les bornes de partition de `app.events` sont interprétées dans le fuseau de la session |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Le schéma appartient à Flyway |
| `spring.jpa.open-in-view` | `false` | Pas de requête SQL pendant la sérialisation JSON |
| `spring.jackson.default-property-inclusion` | `non_null` | Les champs `null` sont omis des réponses (et des messages du stream) |
| `spring.threads.virtual.enabled` | `true` | Threads virtuels pour les requêtes HTTP |
| `spring.docker.compose.enabled` | `false` (`true` en dev) | Docker Compose uniquement en développement |
| `management.endpoints.web.exposure.include` | `health,info` | Seuls ces endpoints Actuator sont exposés |

## Profils

| Profil | Fichier | Contenu |
| --- | --- | --- |
| `dev` | `application-dev.yaml` | Docker Compose actif (`start-only`), clé JWT de dev, migrations + `db/seed` + `db/dev-data` |
| `test` | `application-test.yaml` | Clé JWT de test, migrations + `db/seed` |
| `mock-reco` | aucun | Active `MockRecommendationClient` ; inclus automatiquement par `dev` et `test` |

## Mise en production : check-list

On démarre sans aucun profil et on fournit au minimum :

| Variable | Exemple |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://db:5432/swipread` |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | compte propriétaire des schémas `app` et `reco` (Flyway crée l'extension `vector` et les schémas) |
| `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` (+ `SPRING_DATA_REDIS_PASSWORD`) | instance Redis ≥ 6.2 (`LPOP` avec compteur, `XADD MINID`) |
| `SWIPREAD_JWT_SECRET` | 32 caractères aléatoires ou plus, par exemple `openssl rand -base64 48` |
| `SWIPREAD_RECO_URL` | URL interne du service de reco |
| `SWIPREAD_CORS_ALLOWED_ORIGINS` | origine(s) du frontend web, par exemple `https://app.swipread.fr` ; sans elle, le navigateur bloque tous les appels. CORS autorise les cookies (`Access-Control-Allow-Credentials`), d'où des origines explicites, jamais `*`. Le front et l'API doivent être sur le même site (par exemple `app.swipread.fr` et `api.swipread.fr`) : le cookie `SameSite=Strict` n'est pas envoyé entre deux sites différents |

À vérifier aussi :

- **Premier administrateur.** Le compte `admin@swipread.dev` n'existe qu'en dev et en test. En production, promouvoir un compte existant : `UPDATE app.users SET role = 'ADMIN' WHERE email = '…'`, puis se reconnecter.

- **Swagger.** Swagger UI et `/v3/api-docs` sont publics et actifs par défaut. Les couper si besoin avec `SPRINGDOC_API_DOCS_ENABLED=false` et `SPRINGDOC_SWAGGER_UI_ENABLED=false`.
- **Fuseau.** Le fuseau de la JVM n'a pas d'impact : les dates sont manipulées en `Instant` et les connexions SQL sont en UTC.
- **Plusieurs instances.** Rien n'est conservé en mémoire entre deux requêtes, et le verrou de remplissage est dans Redis. Le job de partitions utilise `CREATE TABLE IF NOT EXISTS`, ce qui le rend sans risque s'il tourne sur toutes les instances.
- **Droits de la reco.** La migration V5 crée le rôle de groupe `swipread_reco` (lecture seule sur `app` sans les colonnes sensibles de `app.users`, écriture sur `reco`, détail dans [la base de données](database.md#points-à-connaître)). Il faut l'autorisation `CREATEROLE` pour l'utilisateur de migration ; sinon, un administrateur exécute d'abord `CREATE ROLE swipread_reco NOLOGIN`. Créer ensuite l'utilisateur de la reco, avec un mot de passe qui ne doit pas figurer dans le dépôt :

  ```sql
  CREATE ROLE reco_service LOGIN PASSWORD '…' IN ROLE swipread_reco;
  ```
