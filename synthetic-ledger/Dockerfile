FROM maven@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn --version && mvn -B -ntp -DskipTests package
ARG RUNTIME_MODULE=ledger-api
RUN case "$RUNTIME_MODULE" in ledger-api|ledger-worker|activity-service) ;; *) exit 2 ;; esac; \
    mkdir /build/extracted && cd /build/extracted && \
    jar -xf "/build/$RUNTIME_MODULE/target/$RUNTIME_MODULE-0.1.0-SNAPSHOT.jar" && \
    case "$RUNTIME_MODULE" in \
      ledger-api) echo dev.dtp.api.LedgerApplication > /build/main-class ;; \
      ledger-worker) echo dev.dtp.worker.LedgerWorker > /build/main-class ;; \
      activity-service) echo dev.dtp.activity.ActivityApplication > /build/main-class ;; \
    esac
RUN javac --release 21 -d /build/health /build/infra/container/ContainerHealth.java

FROM eclipse-temurin@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
# CVE-2026-84782: the pinned upstream base still contains libssl3 .29.
RUN apt-get update && apt-get install -y --no-install-recommends --only-upgrade libssl3=3.0.2-0ubuntu1.30 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/app
COPY --from=build --chown=10001:10001 /build/extracted/BOOT-INF/classes ./classes
COPY --from=build --chown=10001:10001 /build/extracted/BOOT-INF/lib ./lib
COPY --from=build --chown=10001:10001 /build/health ./health
COPY --from=build --chown=10001:10001 /build/main-class ./main-class
COPY --chmod=0555 infra/container/run.sh ./run.sh
ENV SPRING_PROFILES_ACTIVE=container
USER 10001:10001
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=4 \
  CMD ["java", "-Xmx32m", "-cp", "/opt/app/health", "ContainerHealth"]
ENTRYPOINT ["/opt/app/run.sh"]
