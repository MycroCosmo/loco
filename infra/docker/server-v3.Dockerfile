FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace/apps/server-v3
COPY apps/server/package-lock.json /workspace/apps/server/package-lock.json
COPY apps/server/prisma/schema.prisma /workspace/apps/server/prisma/schema.prisma
COPY apps/server-v3/ ./
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew bootJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/apps/server-v3/build/libs/loco-server-v3-*.jar app.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
