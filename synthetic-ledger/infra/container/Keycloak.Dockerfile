FROM quay.io/keycloak/keycloak@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244 AS build
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true

FROM quay.io/keycloak/keycloak@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244
COPY --from=build --chown=1000:1000 /opt/keycloak/ /opt/keycloak/
USER 1000:1000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--http-enabled=true", "--import-realm"]
