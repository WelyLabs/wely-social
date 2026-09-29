# calendar-social-api

Service du **graphe social** de la plateforme [Wely Calendar](https://github.com/WelyLabs/wely-platform) : demandes d'amis, amitiés, statuts relationnels.

C'est le seul service à persistance orientée graphe, et le consommateur de l'événement `USER_CREATED` produit par `calendar-users-api`.

---

## Rôle

| | |
|---|---|
| **Port** | 8083 |
| **Préfixe** | `/social-service` (exposé via la gateway sur `/api/v1/social-service/**`) |
| **Base** | Neo4j (driver réactif) |
| **Consomme** | `USER_CREATED` sur Kafka |

---

## Stack

Java 25 · Spring Boot 4 · WebFlux · Spring Data Neo4j réactif · Spring Cloud Stream (Kafka) · MapStruct · Lombok

---

## Pourquoi un graphe

Les questions que pose un réseau social sont des questions de graphe :

- Qui sont mes amis ?
- Quel est mon statut relationnel avec chaque utilisateur — aucun, demande envoyée, demande reçue, amis ?
- Qui a des amis en commun avec moi ?

En SQL, la troisième impose une auto-jointure de la table des relations, et la deuxième oblige à interroger la relation dans les deux sens puis à réconcilier le résultat en Java. En Cypher, la relation est bidirectionnelle par nature et `startNode(r)` suffit à déterminer qui a initié la demande :

```cypher
MATCH (me:User {userId: $userId})
MATCH (other:User) WHERE other.userId <> me.userId
OPTIONAL MATCH (me)-[r:RELATIONSHIP]-(other)
RETURN other.userId AS userId,
       other.userName AS userName,
       CASE
           WHEN r IS NULL                                       THEN 'NONE'
           WHEN r.status = 'ACCEPTED'                           THEN 'FRIENDS'
           WHEN r.status = 'PENDING' AND startNode(r) = me      THEN 'SENT_BY_ME'
           WHEN r.status = 'PENDING' AND startNode(r) = other   THEN 'SENT_BY_THEM'
           ELSE 'NONE'
       END AS relationStatus
```

Une seule requête renvoie la liste des utilisateurs **et** le statut relationnel de chacun vis-à-vis de l'appelant.

---

## Modèle de graphe

```
        ┌──────────────────────┐                    ┌──────────────────────┐
        │      (:User)         │                    │      (:User)         │
        │  userId              │ ──[:RELATIONSHIP]─▶│  userId              │
        │  userName            │    status          │  userName            │
        │  hashtag             │    createdAt       │  hashtag             │
        │  profilePicUrl       │    acceptedAt      │  profilePicUrl       │
        └──────────────────────┘    rejectedAt      └──────────────────────┘
                                          │
                     PENDING ─────────────┼─────────────▶ ACCEPTED
                        │                                      │
                        ▼                                      ▼
                    REJECTED                            (suppression)
```

**La direction de l'arête porte du sens** : `(demandeur)-[:RELATIONSHIP]->(destinataire)`. C'est ce qui permet de distinguer une demande envoyée d'une demande reçue sans stocker de champ supplémentaire. Les requêtes de lecture d'amitié utilisent une arête **non dirigée** (`-[:RELATIONSHIP]-`), puisqu'une amitié acceptée est symétrique.

Les transitions d'état sont atomiques : accepter une demande est un `SET r.status = 'ACCEPTED'` sur une arête filtrée par `{status: 'PENDING'}`, ce qui rend l'opération idempotente et interdit d'accepter une demande inexistante.

---

## Architecture

Architecture hexagonale, un seul port (`RelationshipRepository`), un adaptateur Neo4j.

```
                 ┌────────────────────────────────────────┐
  HTTP           │            application/rest            │
  ──────────────▶│  RelationshipController · UserController│
                 └────────────────────┬───────────────────┘
                                      │
  Kafka          ┌────────────────────▼───────────────────┐
  ──────────────▶│               domain/                  │
  USER_CREATED   │   services/ RelationshipService (POJO) │
                 │   models/   UserResult (sealed)        │
                 │             ├─ UserNodeDTO             │
                 │             └─ UserSocialDTO           │
                 │   ports/    RelationshipRepository     │
                 └────────────────────▲───────────────────┘
                                      │
                 ┌────────────────────┴───────────────────┐
                 │            infrastructure/             │
                 │  Neo4jRelationshipRepositoryAdapter    │
                 │    → UserNodeRepository (Cypher)       │
                 │    → RelationshipRepository (Cypher)   │
                 └────────────────────┬───────────────────┘
                                      ▼
                                   Neo4j
```

### Type de retour scellé

La liste d'utilisateurs renvoie deux formes selon le filtre demandé : avec statut relationnel (vue « tous les utilisateurs ») ou sans (vue « mes amis »). Plutôt qu'un type commun trop large, une **interface scellée** rend l'ensemble des cas explicite et vérifiable par le compilateur :

```java
public sealed interface UserResult permits UserNodeDTO, UserSocialDTO {}
```

---

## API

Préfixées par `/social-service`, exposées sur `/api/v1/social-service/**`. Toutes exigent un JWT ; l'identité de l'appelant est lue dans le claim `businessId`.

| Méthode | Route | Description |
|---|---|---|
| `GET` | `/users?friendshipStatus=` | Liste d'utilisateurs. Sans paramètre : tous, avec leur statut relationnel. Avec `FRIENDS`, `PENDING_INCOMING` ou `PENDING_OUTGOING` : liste filtrée |
| `POST` | `/relationships/request` | Envoie une demande d'ami par tag (`{"userTag": "theo#4271"}`) |
| `PUT` | `/relationships/accept/{senderId}` | Accepte une demande reçue |
| `PUT` | `/relationships/reject/{senderId}` | Rejette une demande reçue |
| `DELETE` | `/relationships/{friendId}` | Supprime une amitié |

L'identifiant de l'appelant n'est **jamais** accepté en paramètre : il provient systématiquement du token. Les requêtes Cypher sont ancrées sur `(me:User {userId: $userId})`, ce qui rend structurellement impossible d'agir sur la relation d'un tiers.

### Ajout par tag

On n'ajoute pas un ami par UUID mais par son tag public `Pseudo#1234` — l'UUID métier n'a pas à circuler dans l'interface. Le service vérifie l'existence du couple `(userName, hashtag)` avant de créer l'arête.

---

## Événement consommé

**Topic** `USER_CREATED` · groupe `social-service-group`

```json
{ "userId": "…", "userName": "theo", "hashtag": 4271, "profilePicUrl": null }
```

À réception, le service crée le nœud `(:User)` correspondant. C'est ce qui maintient le graphe cohérent avec PostgreSQL sans que `calendar-users-api` ait à connaître ce service.

---

## Gestion des erreurs

| Code | HTTP | Signification |
|---|---|---|
| `SCL_BUS_001` | 400 | Échec d'envoi — utilisateur inexistant ou relation déjà présente |
| `SCL_BUS_002` | 400 | Échec d'acceptation — aucune demande en attente |
| `SCL_BUS_003` | 400 | Échec de rejet — aucune demande en attente |
| `SCL_BUS_004` | 400 | Échec de suppression — aucune amitié |
| `SCL_BUS_005` | 404 | Utilisateur inexistant |
| `SCL_TEC_001` | 500 | Neo4j injoignable |

Les requêtes Cypher renvoient un flux vide quand aucune arête ne correspond ; l'adaptateur transforme ce vide en erreur métier via `switchIfEmpty`.

---

## Configuration

| Variable | Description |
|---|---|
| `NEO4J_URI` | URI Bolt du serveur Neo4j |
| `NEO4J_USERNAME` / `NEO4J_PASSWORD` | Identifiants Neo4j |
| `KAFKA_BOOTSTRAP_SERVER` | Brokers Kafka |
| `KAFKA_KEY` / `KAFKA_SECRET` | Identifiants SASL |
| `KEYCLOAK_ISSUER_URI` | Issuer public — validation de l'émetteur |
| `KEYCLOAK_INTERNAL_JWK_SET_URI` | JWKS interne — récupération des clés |

---

## Démarrage

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Pour lancer **toute la plateforme** (bases, Keycloak, gateway, frontend, les quatre services) en une commande sur un Kubernetes local :

```bash
git clone https://github.com/WelyLabs/wely-gitops-infra && cd wely-gitops-infra
kubectl apply -k overlays/local --server-side
```

---

## Tests

```bash
./mvnw test
./mvnw test jacoco:report      # → target-maven/site/jacoco/
```

8 classes de test : service de domaine, adaptateur, mappers, contrôleurs, gestion d'erreurs.

> **Note build :** ce service produit dans `target-maven/` et non `target/`.

---

## Limites connues

- **La liste d'utilisateurs n'est pas paginée.** `MATCH (other:User)` parcourt tous les nœuds, sans `SKIP`/`LIMIT` ni filtre serveur — le filtrage est fait côté client. À remplacer par une recherche paramétrée, paginée et indexée sur `(userName, hashtag)`.
- **Le consumer Kafka souscrit lui-même au flux.** Le `.subscribe()` dans `KafkaConsumerConfig` retire au framework la gestion de l'acquittement : un échec d'écriture Neo4j perd l'événement sans trace. À remplacer par un retour du flux au framework.
- **Le champ `friendships` de `UserNodeEntity` est toujours vide** : il est mappé sur le type `FRIENDSHIP` alors que toutes les requêtes utilisent `RELATIONSHIP`.
- **Le package s'appelle `infrastucture`** (faute de frappe), renommage en cours.
- **Pas de tests d'intégration** : les requêtes Cypher ne sont jamais exécutées contre un vrai Neo4j.
