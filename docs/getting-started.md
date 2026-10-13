# Prise en main

## Prérequis

- **JDK 25.** Maven est fourni par le wrapper (`./mvnw`), pas besoin de l'installer.
- **Docker Desktop démarré.** Il sert à la base et à Redis en dev, et aux conteneurs des tests d'intégration.
- Optionnel :
  - IntelliJ IDEA ;
  - [Bruno](https://www.usebruno.com/) ou Postman pour les scénarios de test ;
  - Node.js pour lancer ces scénarios en ligne de commande.

## Lancer l'API en local

Le profil **`dev`** est obligatoire. Il :

- démarre PostgreSQL (`pgvector/pgvector:pg17`) et Redis (`redis:7.4`) via `compose.yaml` ;
- fournit une clé JWT de développement ;
- charge des données d'exemple : 8 thèmes et 300 articles ;
- active le faux service de reco (profil `mock-reco`).

**En ligne de commande**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

**Dans IntelliJ :** choisir la configuration partagée **ApiApplication (dev)**, définie dans `.run/`. Si tu utilises une autre configuration, mets `dev` dans *Active profiles*.

Une fois démarrée :

| URL | Rôle |
| --- | --- |
| http://localhost:8080/swagger-ui.html | Swagger UI, pour tester les endpoints à la main |
| http://localhost:8080/v3/api-docs | Spec OpenAPI en JSON |
| http://localhost:8080/actuator/health | État de l'application |

Pour appeler un endpoint protégé depuis Swagger :

1. Appeler `POST /v1/auth/register`.
2. Copier `accessToken` dans le bouton *Authorize*.

## Premier parcours à la main

```bash
H=http://localhost:8080
TOKEN=$(curl -s -X POST $H/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"moi@example.org","password":"password123","displayName":"Moi"}' | jq -r .accessToken)

curl -s $H/v1/topics -H "Authorization: Bearer $TOKEN"
curl -s -X PUT $H/v1/me/topics -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"topicIds":[1,2,3]}'
curl -s $H/v1/feed -H "Authorization: Bearer $TOKEN" | jq '.items[] | {articleId, reason, requestId}'
```

Le premier appel au feed renvoie des articles `reason: "fallback"`. Les suivants viennent du mock de reco, avec `reason: "explore"`. Pour comprendre pourquoi, voir [Architecture](architecture.md#le-feed).

## Profils

| Profil | Rôle |
| --- | --- |
| `dev` | Développement local : Docker Compose, clé JWT de dev, données d'exemple, compte admin, mock de reco |
| `test` | Tests automatisés : Testcontainers, clé JWT de test, thèmes et compte admin seulement, mock de reco |
| `mock-reco` | Remplace l'appel HTTP à la reco par un tirage aléatoire en base, et logge les messages du stream `events` (`Mock reco received …`). Inclus automatiquement par `dev` et `test` |
| *(aucun)* | Production : tout vient des variables d'environnement, voir [Configuration](configuration.md) |

Compte administrateur des profils `dev` et `test` : `admin@swipread.dev` / `password123`.

Sans profil, l'application refuse de démarrer tant que la base, Redis et `SWIPREAD_JWT_SECRET` ne sont pas configurés. C'est voulu : on ne veut pas tourner par erreur avec la configuration de dev.

## Accéder à la base et à Redis

Les ports sont attribués au hasard par Docker. Le plus simple est de passer par `docker compose` :

```bash
docker compose ps                                                   # voir les ports
docker compose exec postgres psql -U swipread -d swipread           # console SQL
docker compose exec redis redis-cli                                 # console Redis
```

Quelques requêtes utiles :

```sql
SELECT id, email, created_at FROM app.users ORDER BY created_at DESC LIMIT 5;
SELECT type, count(*) FROM app.events GROUP BY type;
SELECT * FROM app.user_articles WHERE user_id = 'u_...';
```

```
LLEN feed:u_...                 # taille du stock
LRANGE feed:u_... 0 9           # 10 prochains articles
HGETALL feed:u_...:meta         # origine de chaque article du stock
SCARD seen:u_...                # nombre d'articles déjà servis
XREVRANGE events + - COUNT 5    # derniers messages publiés pour la reco
```

## Arrêter et repartir de zéro

Les conteneurs restent démarrés après l'arrêt de l'application (`lifecycle-management: start-only`), ce qui accélère le redémarrage.

```bash
docker compose down        # arrête les conteneurs (les données sont perdues, pas de volume)
```

## Dépannage

| Symptôme | Cause | Solution |
| --- | --- | --- |
| `Failed to configure a DataSource: 'url' attribute is not specified` | Lancement sans le profil `dev` | Activer le profil `dev`, voir plus haut |
| Même erreur alors que le profil `dev` est actif | Conteneurs créés par une ancienne version de `compose.yaml` sans le label `org.springframework.boot.service-connection` | `docker compose up -d` pour les recréer, puis relancer |
| `Cannot connect to the Docker daemon` | Docker Desktop arrêté | Démarrer Docker Desktop |
| `Port 8080 was already in use` | Une autre instance tourne déjà | L'arrêter, ou lancer avec `--server.port=8089` |
| `Could not resolve placeholder 'SWIPREAD_JWT_SECRET'` ou `Binding validation errors on swipread.jwt` | Secret JWT absent ou trop court (< 32 caractères) | Profil `dev`, ou définir `SWIPREAD_JWT_SECRET` |
| Le feed ne renvoie que des articles `fallback` | Le mock de reco est actif, mais le stock ne s'est pas encore rempli, ou la vraie reco est injoignable | Rappeler le feed après une seconde ; vérifier les logs `Using fallback feed` |
