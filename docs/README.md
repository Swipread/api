# Documentation Swipread API

Swipread est un réseau social type TikTok où l'on scrolle des articles recommandés au lieu de vidéos. Ce dépôt contient l'API backend : comptes, onboarding, feed servi depuis Redis, collecte des événements d'interaction, sauvegardés et historique, administration des thèmes et des sources, et dialogue avec le service de recommandation (la « reco »).

Stack : Java 25, Spring Boot 4.1, PostgreSQL 17 + pgvector, Redis 7.4, Flyway, Spring Security (JWT), springdoc-openapi, Testcontainers.

## Démarrage en 30 secondes

```bash
# Docker doit tourner ; les conteneurs PostgreSQL et Redis sont lancés automatiquement
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Puis ouvrir http://localhost:8080/swagger-ui.html. Détails, IntelliJ et dépannage : [Prise en main](getting-started.md).

## Sommaire

| Document | Pour qui | Contenu |
| --- | --- | --- |
| [Prise en main](getting-started.md) | tous | Prérequis, lancement, profils, accès à la base et à Redis, dépannage |
| [Référence de l'API](api.md) | front, testeurs | Chaque endpoint : requête, réponse, validation, erreurs |
| [Réponses au frontend](reponses-frontend.md) | front | Suite donnée aux 16 demandes du 13/10/2026, changements à intégrer |
| [Architecture](architecture.md) | backend | Composants, packages, fonctionnement du feed et des événements, clés Redis, sécurité |
| [Base de données](database.md) | backend, reco | Schéma Mermaid, tables, partitions, index |
| [Configuration](configuration.md) | backend, ops | Propriétés, variables d'environnement, check-list de mise en production |
| [Tests](testing.md) | backend, testeurs | Tests automatisés, scénarios Bruno et Postman |
| [Décisions et points ouverts](decisions.md) | équipe | Écarts au contrat, interprétations validées, dette connue |
| [Contrat backend / reco](Contrat%20backend%20reco.md) | backend, reco | Contrat d'interface avec la reco : **la référence**, ne pas modifier sans accord |

## Arborescence

```
docs/
├── README.md                 ce sommaire
├── getting-started.md
├── api.md
├── reponses-frontend.md
├── architecture.md
├── database.md
├── configuration.md
├── testing.md
├── decisions.md
├── Contrat backend reco.md
├── bruno/                    scénario de test Bruno
└── postman/                  même scénario pour Postman / Newman
```
