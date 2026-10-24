# wely-social

> **Topic names carry their environment as a prefix** (`dev.`, `prod.`) since 2026-10-24: one
> broker serves every environment and the prefix is all that separates them. A single variable
> carries it — `TOPIC_PREFIX` — and destinations read `${TOPIC_PREFIX}user-created`. No default
> anywhere: a service that cannot name its topics refuses to start rather than joining another
> environment's traffic.

Service du **graphe social** de la plateforme [Wely Calendar](https://github.com/WelyLabs/wely-platform) : demandes d'amis, amitiés, statuts relationnels.

It is the only service with graph persistence, and it consumes the `<env>.user-created`, `<env>.user-modified` and `<env>.user-deleted` events produced by `wely-users`. It produces `FRIENDSHIP_CHANGED`, through a transactional outbox, for wely-notifications and for wely-events' friendship projection.

---

## Rôle

| | |
|---|---|
| **Port** | 8083 |
| **Préfixe** | `/social-service` (exposé via la gateway sur `/api/v1/social-service/**`) |
| **Base** | Neo4j (driver réactif) |
| **Consumes** | `<env>.user-created`, `<env>.user-modified` and `<env>.user-deleted` on Kafka |
| **Produit** | `FRIENDSHIP_CHANGED` sur Kafka |

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

### Communities

Communities hang off the user nodes through two edges, queried with explicit Cypher in `Neo4jCommunityRepositoryAdapter` because a community comes back with lists that a Spring Data Neo4j projection does not map:

```
(:User)-[:MEMBER_OF {role, joinedAt}]->(:Community)
(:Community)-[:INVITED {token, inviterId, createdAt, expiresAt}]->(:User)
```

### Deleted-account marker

A deleted account leaves only a `(:DeletedUser {userId, deletedAt, erasedAt})` node, outside the `:User` label, so no user query ever sees it. It holds only the `businessId` (the pseudonym the other services keep too) and two timestamps. A uniqueness constraint `deleted_user_id` on `userId` is created at startup (`Neo4jSchemaInitializer`, in the background, retried until Neo4j answers).

The marker prevents **resurrection**: the `<env>.user-created` and `<env>.user-modified` upsert starts with `OPTIONAL MATCH (d:DeletedUser {userId: $userId}) WITH d WHERE d IS NULL`, so it never recreates a deleted account's node, even when Kafka delivers those events after `<env>.user-deleted` (three topics, no ordering between them).

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
  <env>.user-created   │   services/ RelationshipService (POJO) │
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

The three consumers (`UserEventsConsumer`) are `Function<Flux<Message<byte[]>>, Mono<Void>>`: the framework subscribes to the stream, never the code. Each record is read from its bytes (`RecordReader`, unknown fields ignored), retried 5 times with a backoff from 1 s capped at 30 s, then logged and skipped, so one failure never ends the stream. An unreadable record, or one without `userId`, is skipped without logging its content.

**Topic** `<env>.user-created` · groupe `social-service-group`

```json
{ "userId": "…", "userName": "theo", "hashtag": 4271, "profilePicUrl": null }
```

À réception, le service crée le nœud `(:User)` correspondant. C'est ce qui maintient le graphe cohérent avec PostgreSQL sans que `wely-users` ait à connaître ce service.

**Topic** `<env>.user-modified` · group `social-service-group`

```json
{ "userId": "…", "userName": "theo", "hashtag": 4271, "firstName": "…", "lastName": "…", "profilePicUrl": null }
```

The whole profile after an edit, applied by the same upsert; it recreates a node that `<env>.user-created` never made. First and last names are not stored in the graph.

**Topic** `<env>.user-deleted` · group `social-service-group` · `start-offset=earliest`

```json
{ "userId": "…", "deletedAt": "2026-10-15T14:03:27.123Z" }
```

Sent when a user deletes their account, or when `wely-users` deletes a user left behind by a registration that Keycloak cancelled. Older records have no `deletedAt`: the processing time stands in for it.

The erasure (`AccountErasureService`) runs in **one Neo4j transaction**:

1. the `(:DeletedUser)` marker is created (the first `deletedAt` is kept);
2. each community the user belongs to is handled as a departure: as its only member, it is deleted; as its last eligible admin, the longest-standing eligible member becomes admin (ties go to the smallest `userId`); if nobody can take over, it is deleted;
3. the invitations the user sent are deleted (`inviterId` is a property, which `DETACH DELETE` does not reach);
4. the user's node is deleted with `DETACH DELETE`: friendships, requests both ways, rejections, memberships and received invitations;
5. the communities the user created that have no member left are deleted;
6. `erasedAt` is set, once.

Eligible means neither an already-deleted account nor a demo account (`ACCOUNT_DELETION_PROTECTED_USER_IDS`). The same rule decides whether other admins remain, so neither an erasure nor a live `leave` leaves a demo account as a community's only admin.

Every step is idempotent: a replayed `<env>.user-deleted` changes nothing. `createdBy` stays on surviving communities, as a pseudonym that is never exposed. Each former friendship gets a `REMOVED` record in the same transaction, without `fromUserName` (see [Produced events](#produced-events)); a replay finds no friendship left and records nothing. Friends are not notified: the person simply disappears from their lists.

Once the node is deleted, a still-valid token can no longer write anything on the user's behalf: every write (requests, acceptances, community creation, joining, invitations) `MATCH`es the user's node.

### Reconciliation

Kafka is not the only path to an erasure. `DeletedUsersReconciliationLoop` pages wely-users' tombstones (`GET /user-service/internal/deleted-users`, 500 per page, header `X-Internal-Secret` from `DELETED_USERS_READER_SECRET`), on the wely-users Service directly: the gateway answers 404 for `/api/v1/*/internal/**`. The first pass runs 1 min after startup; the next one 24 h after a clean pass, or 1 h after a pass that failed or counted a failed erasure. A pass reads the next page only once the current one is reconciled, so a failed read never cuts an erasure short; a pass still running after 24 h (a hung query) is cut and retried 1 h later. Each pass runs the same idempotent erasure (`AccountReconciliationService`) for every tombstone whose marker is missing or has no `erasedAt`, and for every tombstone deleted less than 48 h ago, even if erased, which sweeps a write made meanwhile by a still-valid token. This repairs a `<env>.user-deleted` lost by a crash, skipped after its retries, or expired from the topic before it was read. Each pass logs one INFO line with its counts, and a failed erasure logs only the `businessId`. With a blank `USERS_API_URL` or `DELETED_USERS_READER_SECRET`, every pass fails without any call and the service runs on; `ACCOUNT_ERASURE_RECONCILIATION_ENABLED=false` turns the loop off.

## Produced events

**Topic** `FRIENDSHIP_CHANGED` (overridable with `FRIENDSHIP_TOPIC`) · Kafka key: the pair, both ids sorted and joined with `:`

```json
{ "type": "ACCEPTED", "fromUserId": "…", "fromUserName": "theo", "toUserId": "…",
  "closeness": "FRIEND", "previousCloseness": null,
  "since": "2026-10-18T09:30:00Z", "version": 1760779800123, "occurredAt": "2026-10-18T09:30:00.123Z" }
```

| `type` | Meaning |
|---|---|
| `REQUESTED` | `fromUser` sent a friend request to `toUser` |
| `ACCEPTED` | `fromUser` accepted the request `toUser` had sent (from = acceptor, to = original requester) |
| `REJECTED` | `fromUser` rejected the request `toUser` had sent |
| `CANCELLED` | `fromUser` withdrew its request. Part of the contract; no operation emits it yet |
| `REMOVED` | `fromUser` ended the friendship, or deleted its account |
| `CLOSENESS_CHANGED` | a closeness took effect: an agreed step up, or a one-sided step down by `fromUser`. A mere proposal is not published |
| `SNAPSHOT` | backfill of an existing friendship, shaped like `ACCEPTED`; consumers of this topic ignore it or drop it by version |

| Field | |
|---|---|
| `fromUserName` | the bare name, without hashtag; null when the actor's node is gone (account erasure) |
| `closeness` | the pair's closeness **after** the change, read from the graph in the change's transaction: `FRIEND`, `CLOSE`, `INTIMATE`, or null when they are not friends (`REQUESTED`, `REJECTED`, `REMOVED`) |
| `previousCloseness` | the pair's closeness **before** the change: null when they were not friends, and for `SNAPSHOT`. With `closeness`, it tells an upgrade from a downgrade |
| `since` | when they became friends; null when they are not |
| `version` | rises with every record of a pair, so a consumer drops a record whose version it already holds |
| `occurredAt` | when the change was committed |

**Topic** `FRIENDSHIP_STATE` (overridable with `FRIENDSHIP_STATE_TOPIC`, `cleanup.policy=compact`, created beforehand) · Kafka record key: `a:b`, the two ids sorted

The pair's **current** friendship, for a consumer that needs the state rather than the history (wely-events' friendship projection). The relay writes it from the same outbox record, right after the `FRIENDSHIP_CHANGED` one:

| Record on `FRIENDSHIP_CHANGED` | On `FRIENDSHIP_STATE` |
|---|---|
| `ACCEPTED`, `CLOSENESS_CHANGED`, `SNAPSHOT` | the friendship, below |
| `REMOVED` (account erasure included) | a **tombstone**: the key with a null value |
| `REQUESTED`, `REJECTED`, `CANCELLED` | nothing |

```json
{ "a": "…", "b": "…", "friends": true, "closeness": "CLOSE",
  "since": "2026-10-01T08:00:00Z", "version": 1760779800123 }
```

`version` is the outbox record's, shared with `FRIENDSHIP_CHANGED`: a consumer keeps, per pair, the highest version it has applied and drops a value whose version is not higher (a resend). A tombstone has no body, hence no version: it is applied in offset order, which is the pair's order, since all of a pair's records share its key and partition and the relay sends them in version order.

**Rebuilding.** Compaction keeps at least the last record per key, so the topic always holds every current friendship. A consumer rebuilds its projection by starting a **new consumer group from the earliest offset** (`start-offset=earliest`, or `resetOffsets`) and applying every record in order: a value upserts the pair, a tombstone deletes it. Tombstones themselves are removed after the topic's `delete.retention.ms`, so a consumer started later simply never sees pairs that ended.

### Guarantees

- **Transactional outbox.** A change and its `(:FriendshipOutboxEvent:Unpublished)` record are written in the **same Neo4j transaction** (`TransactionRunner` over the `ReactiveNeo4jTransactionManager` declared in `Neo4jConfig`): both commit, or neither does. The best-effort publisher is gone.
- **Version.** One `(:FriendshipClock)` node hands out `max(previous + 1, now in ms)` to each record, in the record's statement. Writing the clock locks it until commit, so versions follow commit order; the clock outlives relationships, so a pair deleted and re-created keeps rising, and the millisecond floor survives a wiped graph.
- **Relay.** `FriendshipOutboxRelay` sends pending records every 2 s in version order: the `FRIENDSHIP_CHANGED` record, then the `FRIENDSHIP_STATE` one when there is one. It marks the outbox record published only once Kafka has acknowledged **both** (`producer.sync=true` on both bindings); if the second fails, the next attempt sends both again, which consumers drop by version. Delivery is at-least-once: a crash between the sends and the mark sends the record again.
- **Failures.** Each failure is classified (`PublicationErrorClassifier`):
  - *retriable* (broker unreachable, timeout, unknown error): the pass stops there, so no later record of the pair overtakes it; the attempt and the error are counted on the record, and the next passes back off, doubling from 2 s up to 5 min, back to 2 s after a clean pass. From the fifth attempt on, each failure is logged as an error.
  - *permanent* (a message that cannot be built, a serialization failure, `RecordTooLargeException`, `InvalidTopicException`): the record is **parked** at once and the pass goes on with the next one.
  - Any record failing **20 times in a row** (`app.friendship-outbox.relay.max-attempts`) is parked too, whatever the error.

  A parked record loses its `Unpublished` label for `Parked`, gets `parkedAt`, `attempts` and `lastError` (root cause included), is logged at ERROR, and is never sent: inspect with `MATCH (e:FriendshipOutboxEvent:Parked) RETURN e`.
- **Purge.** Published and parked records are deleted after 7 days (hourly pass, 10 000 at most per pass). Pending records are never purged.
- **Snapshot.** At startup, if no `(:Migration {name: <FRIENDSHIP_SNAPSHOT_NAME>})` exists, one `SNAPSHOT` record per accepted pair is enqueued and the marker created, in one statement; each goes to both topics. The default name is `friendship-snapshot-v2`: `v1` ran in dev before `FRIENDSHIP_STATE` existed, and bumping it is what fills the state topic with the friendships made before it. To re-run it later (a lost state topic, a consumer of `FRIENDSHIP_CHANGED` that cannot replay it, the topic keeping records for 3 days only), set `FRIENDSHIP_SNAPSHOT_NAME` to a new value (`friendship-snapshot-v3`, …): the next start enqueues every current friendship once more.

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
| `SCL-TEC-002` | — | Kafka unreachable — internal: raised by the outbox relay in the background, never returned to a client |
| `SCL-TEC-003` | — | wely-users unreachable, or refusing the deleted-accounts listing — internal: raised by the reconciliation in the background, never returned to a client |

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
| `ACCOUNT_DELETION_PROTECTED_USER_IDS` | Demo accounts' `businessId`s, comma-separated canonical UUIDs (any other value fails the startup): never counted as a remaining community admin nor promoted. Empty by default |
| `USERS_API_URL` | In-cluster URL of wely-users, read by the reconciliation (`http://localhost:8082` in the `dev` profile). Empty by default: every pass then fails without a call |
| `DELETED_USERS_READER_SECRET` | Sent as `X-Internal-Secret` to wely-users' deleted-accounts listing. Empty by default: every pass then fails without a call |
| `ACCOUNT_ERASURE_RECONCILIATION_ENABLED` | Runs the reconciliation loop, `true` by default |
| `FRIENDSHIP_TOPIC` | Topic produit, `FRIENDSHIP_CHANGED` par défaut |
| `FRIENDSHIP_STATE_TOPIC` | Compacted state topic, `FRIENDSHIP_STATE` by default |
| `FRIENDSHIP_OUTBOX_RELAY_ENABLED` | Runs the `FRIENDSHIP_CHANGED` outbox relay, `true` by default |
| `FRIENDSHIP_SNAPSHOT_NAME` | Name of the one-time snapshot's marker, `friendship-snapshot-v2` by default; a new value re-runs the snapshot once |
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
- **A lost orphan `<env>.user-deleted` is not replayed.** The reactive binder acknowledges a record when it enters the stream, so a shutdown mid-erasure, or six failures in a row, lose it. The reconciliation repairs a user-requested deletion, but an abandoned registration leaves no tombstone in wely-users, so nothing re-runs its erasure.
- **A parked record is never delivered, and the records after it overtake it.** Parking stops one bad record from holding back every later change, at a price: consumers miss that change, and a later record of the same pair goes out before it. On `FRIENDSHIP_STATE` the next change of the pair repairs the state; until then, or if there is none, it stays stale. Nothing re-sends a parked record: fix the cause, then remove the `Parked` label and add `Unpublished` back, or re-run the snapshot.
- **A long broker outage parks records.** Retriable failures also count towards the 20-attempt limit. With the backoff, the head record is parked after roughly an hour of outage, and each record behind it after about 1 h 35 more. A retriable error is retried in strict version order meanwhile, so an outage still delays every later change until it ends or the record is parked.
- **Le champ `friendships` de `UserNodeEntity` est toujours vide** : il est mappé sur le type `FRIENDSHIP` alors que toutes les requêtes utilisent `RELATIONSHIP`.
