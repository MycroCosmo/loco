# Same codec/runtime stage as deployment, with an explicitly separate perf artifact.
FROM loco-perf-codec:8.15.3
WORKDIR /app
RUN mkdir /app/uploads && chown 1000:1000 /app/uploads
COPY apps/server-v3/build/libs/loco-server-v3-0.0.1-SNAPSHOT-perf-local-only.jar app.jar
USER 1000:1000
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
