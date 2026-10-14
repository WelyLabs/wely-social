# wely-social

Service du **graphe social** de la plateforme [Wely Calendar](https://github.com/WelyLabs/wely-platform) : demandes d'amis, amitiés, statuts relationnels.

C'est le seul service à persistance orientée graphe, et le consommateur des événements `USER_CREATED` et `USER_DELETED` produits par `wely-users`.

---

## Rôle

| | |
|---|---|
| **Port** | 8083 |
| **Préfixe** | `/social-service` (exposé via la gateway sur `/api/v1/social-service/**`) |
| **Base** | Neo4j (driver réactif) |
| **Consomme** | `USER_CREATED` et `USER_DELETED` sur Kafka |

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

### Niveau de proximité

Une amitié acceptée porte un niveau, `FRIEND` < `CLOSE` < `INTIMATE`, stocké sur l'arête :

| Propriété | Sens |
|---|---|
| `closeness` | Niveau convenu par les deux. Absent = `FRIEND` |
| `proposedCloseness` | Niveau supérieur demandé par l'un, en attente de l'autre |
| `proposedBy` | `userId` de celui qui l'a demandé |
| `closenessVersion` | Incrémenté à chaque changement. Absent = 0 |

Les règles vivent dans le domaine (`Friendship`), pas dans le Cypher :

- **monter** d'un niveau est une proposition, appliquée seulement quand l'autre l'accepte — ou la demande à son tour ;
- **descendre** s'applique tout de suite, sans l'accord de l'autre, et annule toute proposition : on ne doit jamais avoir besoin de l'autre pour reprendre sa vie privée ;
- redemander son niveau actuel retire sa propre proposition.

Le service lit l'amitié, décide, puis écrit **à condition que la version n'ait pas bougé** (verrou optimiste) : si les deux répondent en même temps, la seconde écriture ne trouve rien et reçoit un 409 plutôt que d'écraser la première.

Ce que chaque niveau dévoile d'un calendrier ne se décide pas ici : c'est `wely-events` qui le lit (`GET /relationships/{friendId}`) et l'applique. Supprimer une amitié supprime l'arête, donc le niveau : redevenir amis repart de `FRIEND`.

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
| `GET` | `/users?friendshipStatus=` | Liste d'utilisateurs. Sans paramètre : tous, avec leur `hashtag`, leur statut relationnel et, pour une demande en attente, `viaEventId`. Avec `FRIENDS`, `PENDING_INCOMING` ou `PENDING_OUTGOING` : liste filtrée ; les demandes en attente portent aussi `viaEventId` |
| `GET` | `/users/{userId}` | Profil d'un utilisateur vu de l'appelant : `relationStatus` (`SELF`, `NONE`, `FRIENDS`, `SENT_BY_ME`, `SENT_BY_THEM`) et, entre amis seulement, `closeness`, `proposal`, `proposedByFriend` et `since` (date d'acceptation, à défaut de création). 404 `SCL-BUS-005` si inconnu |
| `GET` | `/users/{userId}/mutual` | Amis communs à l'appelant et à `userId` (relations acceptées), triés par pseudo, 50 au plus. Ouvert aussi entre non-amis. 404 `SCL-BUS-005` si inconnu |
| `POST` | `/relationships/request` | Envoie une demande d'ami par tag (`{"userTag": "theo#4271"}`). `viaEventId`, facultatif, garde l'événement d'où part la demande, sur la relation ; il n'est pas vérifié |
| `PUT` | `/relationships/accept/{senderId}` | Accepte une demande reçue |
| `PUT` | `/relationships/reject/{senderId}` | Rejette une demande reçue |
| `DELETE` | `/relationships/{friendId}` | Supprime une amitié |
| `GET` | `/relationships` | Mes amis, avec le niveau et la proposition en cours |
| `GET` | `/relationships/{friendId}` | Une amitié vue de l'appelant, avec `since` (404 si pas amis). Appelée par `wely-events` |
| `PUT` | `/relationships/{friendId}/closeness` | Demande un niveau (`{"closeness": "CLOSE"}`) : propose s'il est plus haut, applique s'il est plus bas |
| `PUT` | `/relationships/{friendId}/closeness/accept` | Accepte le niveau proposé par l'ami |
| `PUT` | `/relationships/{friendId}/closeness/decline` | Refuse le niveau proposé par l'ami |

L'identifiant de l'appelant n'est **jamais** accepté en paramètre : il provient systématiquement du token. Les requêtes Cypher sont ancrées sur `(me:User {userId: $userId})`, ce qui rend structurellement impossible d'agir sur la relation d'un tiers.

### OpenAPI

La spécification est générée par `springdoc-openapi` et servie sans jeton :

| | |
|---|---|
| Spec JSON | `http://localhost:8083/v3/api-docs` |
| Swagger UI | `http://localhost:8083/swagger-ui.html` |

Ces deux chemins ne sont **pas** routés par la gateway, et le Service est en `ClusterIP` : rien
hors du cluster ne peut les atteindre. La documentation reste donc active en permanence — c'est
la topologie réseau qui la protège, pas un drapeau.

> Le préfixe de chemin du service est appliqué par package (`…application.rest`) et non par
> annotation. Sélectionner sur `@RestController` attrapait aussi le contrôleur de springdoc, ce
> qui déplaçait la spec en `/social-service/v3/api-docs` derrière l'authentification.

### Ajout par tag

On n'ajoute pas un ami par UUID mais par son tag public `Pseudo#1234` — l'UUID métier n'a pas à circuler dans l'interface. Le service vérifie l'existence du couple `(userName, hashtag)` avant de créer l'arête.

---

## Événements consommés

**Topic** `USER_CREATED` · groupe `social-service-group`

```json
{ "userId": "…", "userName": "theo", "hashtag": 4271, "profilePicUrl": null }
```

À réception, le service crée le nœud `(:User)` correspondant. C'est ce qui maintient le graphe cohérent avec PostgreSQL sans que `wely-users` ait à connaître ce service.

**Topic** `USER_DELETED` · groupe `social-service-group`

```json
{ "userId": "…" }
```

Envoyé quand `wely-users` supprime un utilisateur laissé par une inscription que Keycloak a annulée. Le service supprime le nœud et toutes ses relations (`DETACH DELETE`). Supprimer un utilisateur absent n'est pas une erreur : l'événement peut être rejoué.

---

## Gestion des erreurs

| Code | HTTP | Signification |
|---|---|---|
| `SCL-BUS-001` | 400 | Demande refusée — utilisateur inexistant ou relation déjà présente |
| `SCL-BUS-002` | 400 | Rien à accepter — aucune demande en attente |
| `SCL-BUS-003` | 400 | Rien à rejeter — aucune demande en attente |
| `SCL-BUS-004` | 400 | Aucune amitié à supprimer |
| `SCL-BUS-005` | 404 | Utilisateur inexistant |
| `SCL-BUS-006` | 400 | Handle malformé — le format est `Nom#1234` |
| `SCL-BUS-007` | 404 | Pas d'amitié acceptée avec cet utilisateur |
| `SCL-BUS-008` | 400 | Rien à accepter ou refuser — l'ami n'a proposé aucun niveau |
| `SCL-BUS-009` | 409 | L'amitié a changé entre la lecture et l'écriture — recharger et réessayer |
| `SCL-VAL-001` | 400 | Validation du corps de requête, détail par champ |
| `SCL-REQ-000` | *repris* | Chemin inconnu, méthode non autorisée |
| `SCL-TEC-001` | 502 | Neo4j injoignable |

**502 et non 500** pour l'indisponibilité du graphe : la requête était valide, c'est le magasin
qui est tombé.

Les requêtes Cypher renvoient un flux vide quand aucune arête ne correspond ; l'adaptateur
transforme ce vide en erreur métier via `switchIfEmpty`.

> `switchIfEmpty(Mono.error(…))` évalue son argument **à l'assemblage**, pas à la souscription.
> Construire l'exception directement dedans la crée à chaque appel, y compris quand tout va
> bien : d'où le `Mono.defer(…)` systématique dans les adaptateurs.

Toutes les réponses d'erreur sont des `ProblemDetail` (RFC 7807), avec un `code` stable qu'un
client peut tester et un `timestamp` :

```json
{
  "type": "https://welylabs.app/problems/scl-bus-005",
  "title": "User not found",
  "status": 404,
  "detail": "No user matches the given handle.",
  "instance": "/social-service/users/Alice%231234",
  "code": "SCL-BUS-005",
  "timestamp": "2026-09-30T19:23:43.598673Z"
}
```

> `SCL-REQ-000` rend le statut d'origine d'une `ResponseStatusException` — 404 sur un
> chemin inconnu, 405 sur une méthode non autorisée. Sans lui, le handler `Exception.class`
> les avalait toutes et **tout chemin inconnu répondait 500**. C'est le genre de défaut qu'un
> test de route nominale ne voit jamais.
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
- **Pas de tests d'intégration** : les requêtes Cypher ne sont jamais exécutées contre un vrai Neo4j.
