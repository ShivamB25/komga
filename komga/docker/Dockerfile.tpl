FROM eclipse-temurin:17-jre AS builder
ARG JAR={{distributionArtifactFile}}
WORKDIR /builder
COPY assembly/${JAR} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# amd64 builder
FROM ubuntu:26.04 AS build-amd64
ENV JAVA_HOME=/opt/java/openjdk
COPY --from=eclipse-temurin:23-jre $JAVA_HOME $JAVA_HOME
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN apt -y update && \
    apt -y install ca-certificates locales libjxl-dev libheif-dev libwebp-dev libarchive-dev wget curl && \
    echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen && \
    locale-gen en_US.UTF-8 && \
    wget "https://github.com/pgaskin/kepubify/releases/latest/download/kepubify-linux-64bit" -O /usr/bin/kepubify && \
    chmod +x /usr/bin/kepubify && \
    apt -y autoremove && rm -rf /var/lib/apt/lists/*
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/x86_64-linux-gnu"

# arm64 builder
FROM ubuntu:26.04 AS build-arm64
ENV JAVA_HOME=/opt/java/openjdk
COPY --from=eclipse-temurin:23-jre $JAVA_HOME $JAVA_HOME
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN apt -y update && \
    apt -y install ca-certificates locales libjxl-dev libheif-dev libwebp-dev libarchive-dev wget curl && \
    echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen && \
    locale-gen en_US.UTF-8 && \
    wget "https://github.com/pgaskin/kepubify/releases/latest/download/kepubify-linux-arm64" -O /usr/bin/kepubify && \
    chmod +x /usr/bin/kepubify && \
    apt -y autoremove && rm -rf /var/lib/apt/lists/*
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/aarch64-linux-gnu"

# arm builder: uses temurin-17, as arm32 support was dropped in JDK 21
FROM eclipse-temurin:17-jre AS build-arm
RUN apt -y update && \
    apt -y install wget curl && \
    wget "https://github.com/pgaskin/kepubify/releases/latest/download/kepubify-linux-arm" -O /usr/bin/kepubify && \
    chmod +x /usr/bin/kepubify && \
    apt -y autoremove && rm -rf /var/lib/apt/lists/*

FROM build-${TARGETARCH} AS runner
VOLUME /config
WORKDIR /app
COPY --from=builder /builder/application.jar ./application.jar
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./
COPY komga-entrypoint.sh /usr/local/bin/komga-entrypoint
RUN chmod +x /usr/local/bin/komga-entrypoint
ENV KOMGA_CONFIGDIR="/config"
ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'
ENTRYPOINT ["/usr/local/bin/komga-entrypoint"]
EXPOSE 25600
LABEL org.opencontainers.image.source="https://github.com/gotson/komga"
