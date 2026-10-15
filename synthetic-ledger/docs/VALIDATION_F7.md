# F7 local validation — acceptance pending

15 October 2026. F7 remains open until the hardened Kubernetes manifests pass
functional bootstrap, smoke and recovery checks. F8 has not started.

| Gate | Actual result |
| --- | --- |
| Clean Java 21 integration regression after all patches | 142 tests passed, zero failures/errors/skips |
| Plain Maven verify after integration | Passed; 52 unit/slice tests |
| OpenAPI and actual event contracts | Passed |
| Final API, worker and Activity builds | Passed |
| Optimized Keycloak 26.8.1 build | Passed |
| Trivy image scans of all four custom images | Zero fixable HIGH/CRITICAL findings |
| Final infrastructure configuration scan | Zero HIGH/CRITICAL findings; no ignore rules |
| Source secret scan | Passed for HIGH/CRITICAL; excludes generated targets, private local secrets, provider cache and Git internals |
| Terraform fmt/init without backend/validate | Passed |
| Terraform mocked plan-only security tests | Two passed; no account operations |
| Helm lint/render local, test and future/package | Passed |
| Four-worker chart rejection | Rejected as expected; laboratory has three partitions |
| Compose configuration | Passed; new optimized runtime still needs functional proof |
| Workflow YAML/actionlint 1.7.12 | Passed; shellcheck/pyflakes disabled locally |
| Hosted GitHub workflow/CodeQL | Not executed |
| Hardened Kubernetes rollout, scale2 and native PKCE smoke | Passed |
| API replacement routing | 100/100 HTTP200, zero transport errors; not financial in-flight draining |
| Worker replacement and PostgreSQL PVC recovery | Pods recovered; subsequent smoke passed, four POSTED/four journals and zero invariant violations |
| First native login immediately after database recovery | HTTP400; subsequent attempt passed; root cause not established |

Safe aggregate evidence is in `evidence/f7-security-progress.json`. Native
execution logs and report properties remain private under the task's `work`
directory. Earlier interrupted test attempts are not included in the passing
regression. Vulnerability scan scope uses `--ignore-unfixed`; this does not
establish that images contain no vulnerabilities of any severity.

The 14 October Compose and kind results remain available separately in
`evidence/f7-compose-validation.json` and `f7-kind-before-hardening.json`.
They do not establish that the 15 October filesystem restrictions work.
Kind's default CNI does not enforce NetworkPolicy. Single-node PVC recovery is
not a backup/restore or multi-zone resilience test. Cloud deployment, account
IAM/connectivity/costs and real Terraform plans remain outside authorization.

`local-drain-check.py` checks liveness routing while replacing one API pod with
two replicas. It explicitly does not measure financial in-flight draining.
The posting/recovery/commit-before-ack assertions are covered separately by the
fresh Kafka integration tests. F6 performance measurements predate security
patches and remain frozen historical results.

Hardened evidence: `evidence/f7-kind-hardened.json`. The first native login after PostgreSQL pod replacement returned HTTP400. A separate subsequent attempt passed without configuration changes; the failure is retained. This does not demonstrate seamless identity availability or establish the root cause. Final local F7 acceptance and packaging remain pending review of this limitation.

Sanitized Keycloak diagnostics at the failed login show PostgreSQL closed-connection exceptions and administrator termination, alongside native `invalid_user_credentials`. This is consistent with stale database connections during recovery, but no controlled causal experiment was performed. No authentication rules or financial timeouts were relaxed. The subsequent native login succeeded.
