# ADR-006 — isolated runtime laboratory and static cloud blueprint

Status: decision implemented; final F7 functional validation pending.

Each application process uses a multistage Java 21 image, a non-root identity,
bounded heap, read-only root and explicit temporary storage. The posting worker
exposes only health in its container profile; its normal profile remains
non-web. Readiness depends on the process's actual dependencies, while liveness
must not restart the process merely because PostgreSQL, Kafka, Redis or MongoDB
is unavailable. Worker readiness also requires an assigned running consumer.

Compose and kind are separate isolated local demonstrations, sharing only
loopback port numbers, not live database endpoints or volumes. Bootstrap uses
owner credentials for migrations and then applies dedicated process grants.
Runtime containers do not own the schema. Kafka client traffic is authenticated
TLS with separate ACLs; Mongo and Redis use dedicated credentials. All nonce,
certificate, realm and environment material remains ignored and local.

Helm sequences bootstrap before runtime admission. It bounds worker scale by
the three topic partitions, configures startup/readiness/liveness independently,
and budgets graceful termination for in-flight work. PVC-backed single-node
services demonstrate persistence and pod replacement, not production high
availability. Kind's default CNI does not establish NetworkPolicy enforcement.
Optimized Keycloak builds remove startup augmentation from a read-only runtime.

The Terraform model separates network, data/messaging and application identities
by lifecycle. It intentionally contains dummy account identifiers, invalid
external endpoints and placeholder images. Only format, schema and mocked
plan-only tests are permitted. Incomplete SCRAM setup, external dependency
connectivity, account policies, trust, migrations and recovery prevent any claim
that it is deployment-ready. No real account operations are part of this ADR.

CI builds and checks code, contracts, charts, static infrastructure and local
images with minimal permissions. It has no deploy or image-push step. Raw JVM
test reports can contain secrets, so only whitelisted counts are exported.
Hosted execution is separate evidence and remains pending publication.
