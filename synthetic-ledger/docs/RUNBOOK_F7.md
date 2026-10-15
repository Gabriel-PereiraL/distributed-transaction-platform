# F7 isolated local infrastructure

Status: implementation and validation in progress. F7 is not accepted yet.
F8 and hosted CI are pending. No AWS deployment is authorized.

## Build

Run from `synthetic-ledger`, with Docker Desktop and Java 21 available:

```powershell
foreach ($module in @('ledger-api','ledger-worker','activity-service')) {
    docker build --build-arg "RUNTIME_MODULE=$module" -t "dtp-${module}:f7" .
    if ($LASTEXITCODE -ne 0) { throw 'Runtime build failed' }
}
docker tag dtp-activity-service:f7 dtp-activity:f7
docker build -f infra/container/Keycloak.Dockerfile -t dtp-keycloak:f7 .
if ($LASTEXITCODE -ne 0) { throw 'Keycloak build failed' }
```

Images remain local. No registry push, cloud credentials or deployment jobs are
needed. The explicit libssl3 package revision may stop being available in the
upstream archive; fail the build and review a new pinned base/revision instead
of silently removing the security update.

## Bootstrap and Kubernetes

Prepare the ignored `.local-secrets` directory with
`infra/local/prepare-secrets.ps1 -Keytool <Java21-keytool>`. Never export this
directory, generated environments, kubeconfig or raw test report properties.
The initial PostgreSQL migration uses the dedicated owner role; the public API
does not migrate the database or receive owner credentials.

Create only the dedicated `dtp-f7` kind cluster using `infra/local/kind.yaml`
and the private kubeconfig under `.local-secrets/kind.kubeconfig`. Use the
verified kind, kubectl and Helm binaries. Run `infra/local/deploy-kind.ps1`
with its explicit `-Kubectl`, `-Helm`, `-Kind` and `-Python` paths.
It checks the dedicated cluster and loopback Kubernetes server, loads local
images, injects private secrets through a pipe, waits for migration/grants,
Kafka ACL and Mongo replica-set jobs, reconciles the local OIDC realm, then
enables the three application processes.

The loopback demo ports are Keycloak 18080, Ledger API 18085 and Activity 18086.
Compose and kind use these same ports: stop the owned Compose project before
starting kind. Never stop unrelated projects. Execute `scripts/local-smoke.py`
against the isolated lab to verify native PKCE, admission, replay and the
terminal Mongo projection. Do not print JWTs or passwords.

Readiness checks dependencies; liveness checks the process. A failed dependency
must remove readiness without creating an unnecessary process restart. Worker
replicas are bounded by the three Kafka partitions. Test values use two API
and two worker replicas; four worker replicas must fail chart rendering.

Pod recovery must preserve the financial invariants and PVC data. Local kind
uses a single node and its default CNI does not enforce NetworkPolicy; no
multi-zone availability or network-policy enforcement is demonstrated here.

### Measured identity recovery

After restarting Docker/the dedicated node, wait for actual workload readiness;
cached deployment counts can briefly describe the previous running node.
Run `scripts/local-identity-recovery.py --kubectl <verified-binary>
--kubeconfig .local-secrets/kind.kubeconfig --output <private-result.json>` with
the local Python environment containing requests. It refuses external clusters,
waits for all eight workloads, requires a successful baseline PKCE-to-projection
smoke, and only then replaces `postgres-0` while retaining its PVC.

The check records PostgreSQL readiness time, up to three separate native-login
and synthetic-transfer smoke attempts, and final SQL invariants. Every failed
attempt remains in the result. Recovery time includes the full smoke and is an
observed upper bound for this run, not a production RTO. A successful later
attempt does not establish seamless identity availability. The check never
restarts Keycloak or changes authentication to force a passing result. Each
successful smoke adds one synthetic 25-cent transfer; preserve this in counts.

## Validation and evidence

Run Helm lint and render bootstrap/local/test/future values. Verify actual
bootstrap, smoke, worker replacement, two-replica rollout and PostgreSQL PVC
recovery, recording synthetic counts and invariants only. Database/filesystem
hardening changes require a new functional validation, even after static scans
pass. Never reuse earlier evidence as proof of a changed image or manifest.

Run `./mvnw clean verify -Pintegration`, contract validation, and then
`./mvnw verify`. `scripts/collect-test-summary.py <output.json>` exports counts
only; run it immediately after a clean integration build to avoid combining
new unit reports with stale integration reports. Never upload entire targets.

Run Trivy 0.76.0 config and image scans, HIGH/CRITICAL with a nonzero exit code
on findings. Image scan scope uses `--ignore-unfixed`; retain that limitation
in evidence. A scan timeout is a failed execution, not a passing security gate.

Terraform uses `infra/terraform/validate-static.ps1 -Terraform <binary>` only:
format check, initialization without backend, validate and mocked plan-only
tests. The future blueprint remains incomplete and is not deployable as-is.
Never run a real account plan, apply or any AWS resource operation.

The GitHub workflow contains test/build/scan jobs, no cloud deployment or image
push. Action commits and scanner archive checksums are pinned. Hosted execution
and CodeQL results must be recorded separately after publication; local YAML
parsing does not prove either passed.
