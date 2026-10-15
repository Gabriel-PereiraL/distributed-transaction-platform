# F7 security remediation — validation in progress

Current evidence (15 October): the final patched dependency graph passed a
clean 142-test integration regression, 52-test plain verify and actual event/API
contracts. All four custom image scans and the infrastructure scan passed their
documented HIGH/CRITICAL scope. Hardened kind bootstrap and smoke passed; the
identity recovery limitation remains under review in VALIDATION_F7.md. Hosted
CI/CodeQL have not run. The paragraphs below retain the investigation history;
their rerun requirements were satisfied by those final local executions.

The first completed API vulnerability scan found nine fixable HIGH/CRITICAL
records in libssl3, embedded Tomcat and Jackson. The earlier five-minute scan
timed out downloading the Java database and is not a vulnerability result.

The pinned Temurin base still resolves to the same digest on 15 October 2026.
The runtime Dockerfile therefore installs the explicit Ubuntu libssl3 security
revision `3.0.2-0ubuntu1.30`. Tomcat `11.0.25` and Jackson BOM `3.1.7` are narrowly
scoped Maven property overrides pending incorporation into the Spring Boot BOM.
The complete dependency graph and runtime images must be tested and scanned
again before accepting this gate. A successful plain verify covers 52 tests;
the 90 existing integration reports are historical until rerun after the update.

The first worker scan additionally found `CVE-2026-106451` in Kafka's transitive
`at.yawk.lz4:lz4-java:1.10.1`. Dependency management now selects `1.11.4`;
the [upstream security release](https://github.com/yawkat/lz4-java/releases/tag/v1.11.4)
confirms the fix. The initial integration rerun was interrupted to consolidate
this patch before starting a new clean final regression. It is not a test pass.

Infrastructure scan initially found 15 HIGH records. Jobs now run with explicit
non-root identities, dropped capabilities and read-only roots. Database vendor
entrypoints retain their ownership initialization, while writable mounts are
limited to data, temporary files, PostgreSQL sockets and Kafka logs. Keycloak
uses a separately built optimized image, with explicit writable data/tmp mounts,
instead of augmenting its installation at startup. The static scan after these
changes found no HIGH/CRITICAL records; actual Kubernetes bootstrap and smoke
tests are still required. No ignore rules or vulnerability exemptions were added.

Scanning the custom Keycloak image found six fixable HIGH records in the
26.8.0 base's PCRE2 packages. The runtime Dockerfile and identity integration
fixture now pin the official 26.8.1 image digest
`79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244`.
The [26.8.1 release](https://www.keycloak.org/2026/10/keycloak-2681-released)
was published on 13 October. Build, scan, PKCE and integration checks must pass
for this patch before acceptance. The preceding regression attempt was stopped
to finalize this fixture; it does not establish a final passing test count.

The Terraform blueprint specifies a dedicated Kafka KMS key with rotation and a
30-day deletion window. Formatting, validation and both mocked plan-only tests
passed again. This does not create a key, contact an AWS account or establish
that the incomplete blueprint is deployable.

CI exports only whitelisted test counts, never raw Surefire/Failsafe XML,
properties, logs, credentials or recordings. Hosted CI and CodeQL execution
remain pending publication. Local image scans intentionally cover fixable
HIGH/CRITICAL vulnerabilities; this is not a claim of zero vulnerabilities.

F6 measurements and frozen archives precede these security patch overrides.
They remain historical benchmark evidence, not a fresh performance measurement
of the F7 dependency graph. No new throughput or capacity claim is made here.

Primary references checked on 15 October 2026:

- [Tomcat 11 security advisories](https://tomcat.apache.org/security-11)
- [Jackson 3.1.7 release notes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7)
- [Ubuntu OpenSSL security notice for Jammy and revision .30](https://ubuntu.com/security/notices/USN-8847-1)
- [Optimized Keycloak container build](https://www.keycloak.org/server/containers)
