# === Build Stage (네이티브 플랫폼에서 실행, QEMU 에뮬레이션 없음) ===
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./

RUN chmod +x gradlew

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon || true

COPY src/ src/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -x test

# === Layer Extraction Stage ===
FROM eclipse-temurin:21-jre-alpine AS extractor
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# === Runtime Stage ===
FROM bellsoft/liberica-openjre-alpine:21

WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/ ./

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8084

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "org.springframework.boot.loader.launch.JarLauncher"]
