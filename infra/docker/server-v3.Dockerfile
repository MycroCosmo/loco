FROM eclipse-temurin:21-jre-jammy AS vips-build
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential pkg-config meson ninja-build curl ca-certificates \
    libglib2.0-dev libexpat1-dev libjpeg-dev libpng-dev libwebp-dev libheif-dev libexif-dev liblcms2-dev
WORKDIR /native
RUN curl -fsSL https://github.com/libvips/libvips/releases/download/v8.15.3/vips-8.15.3.tar.xz -o vips.tar.xz \
    && echo '3e27d9f536eafad64013958fe9e8a1964c90b564c731d49db7c1a1c11b1052a0  vips.tar.xz' | sha256sum -c - \
    && tar -xf vips.tar.xz \
    && meson setup vips-8.15.3/build vips-8.15.3 --prefix=/opt/vips --libdir=lib \
       -Dcplusplus=false -Dexamples=false -Dintrospection=disabled -Dmodules=disabled \
       -Djpeg=enabled -Dpng=enabled -Dwebp=enabled -Dheif=enabled \
    && meson compile -C vips-8.15.3/build -j 2 \
    && meson install -C vips-8.15.3/build

FROM eclipse-temurin:21-jre-jammy AS codec-runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    libglib2.0-0 libexpat1 libjpeg-turbo8 libpng16-16 libwebp7 libwebpmux3 libwebpdemux2 libheif1 libexif12 liblcms2-2 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=vips-build /opt/vips /opt/vips
ENV PATH="/opt/vips/bin:${PATH}" LD_LIBRARY_PATH="/opt/vips/lib"

FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace/apps/server-v3
COPY apps/server/package-lock.json /workspace/apps/server/package-lock.json
COPY apps/server/prisma/schema.prisma /workspace/apps/server/prisma/schema.prisma
COPY apps/server-v3/ ./
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew bootJar --no-daemon --console=plain

FROM codec-runtime AS runtime
WORKDIR /app
RUN mkdir /app/uploads && chown 1000:1000 /app/uploads
COPY --from=build /workspace/apps/server-v3/build/libs/loco-server-v3-*.jar app.jar
USER 1000:1000
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
