# F7 sources and tool provenance

Consulted 14–15 October 2026. These references support implementation choices,
not a claim of production deployment or successful hosted CI.

| Topic | Primary source |
| --- | --- |
| Multistage images | [Docker build documentation](https://docs.docker.com/build/building/multi-stage/) |
| Startup, readiness, liveness | [Kubernetes probe configuration](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/) |
| Shutdown hooks | [Kubernetes container lifecycle](https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/) |
| Local cluster | [kind quick start](https://kind.sigs.k8s.io/docs/user/quick-start/) |
| Helm installation/checksums | [Helm installation](https://helm.sh/docs/intro/install/) |
| Mocked infrastructure tests | [HashiCorp provider mocking](https://developer.hashicorp.com/terraform/language/tests/mocking) |
| MSK supported versions | [AWS MSK version documentation](https://docs.aws.amazon.com/msk/latest/developerguide/supported-kafka-versions.html) |
| Provider resource schema | [Pinned AWS provider MSK documentation](https://raw.githubusercontent.com/hashicorp/terraform-provider-aws/v6.69.0/website/docs/r/msk_cluster.html.markdown) |
| Security patch versions | [Tomcat advisories](https://tomcat.apache.org/security-11), [Jackson 3.1.7](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7), [Ubuntu Jammy libssl3](https://ubuntu.com/security/notices/USN-8847-1), [LZ4 1.11.4](https://github.com/yawkat/lz4-java/releases/tag/v1.11.4) |
| Optimized OIDC image | [Keycloak containers](https://www.keycloak.org/server/containers), [Keycloak 26.8.1](https://www.keycloak.org/2026/10/keycloak-2681-released) |

Tool downloads were verified against their official release checksums or GitHub
release asset SHA-256 metadata before execution. These are local tool artifacts,
not application credentials. The workflow pins its Linux download hashes and
all third-party actions to full commit SHAs.

| Artifact | SHA-256 |
| --- | --- |
| kind 0.33.0 Windows executable | `4b22adaa135368c5a465d56bbd8e520cbea87272a06ca00b6078e7b81515c9fc` |
| kubectl 1.37.0 Windows executable | `4721b614a67bb4932a0369e61f4a323d8c6ca00943d3a2ff14837c124da06f0e` |
| Helm 4.3.0 Windows ZIP | `304ea163cce4d9ad14e189c01846c6a34de9cfdfe48536ae54b2e8ba7884e67c` |
| Helm 4.3.0 Linux tar.gz | `86584a54def73570558f66f5111cc53dfed56689637ae32c1201205d494f54fb` |
| Terraform 1.16.6 Windows ZIP | `6ea2c186e0e76a113af30fbcdcf7a4b4c70756317994a04929e5c90b9e94b1df` |
| Trivy 0.76.0 Windows ZIP | `7b4762a5a5326cb4b83dfd2bb5d9aaf15a1ed867c75cad7dd8b38840a28e7be6` |
| Trivy 0.76.0 Linux tar.gz | `e24b1c51293c1b15e4ba8d45bbc7891ba053f90278ec2698f3562c993e145fe7` |
| actionlint 1.7.12 Windows ZIP | `6e7241b51e6817ea6a047693d8e6fed13b31819c9a0dd6c5a726e1592d22f6e9` |

Version selection and hashes do not replace scans or functional testing.
Frozen F5/F6 artifacts retain their original versions and evidence.
