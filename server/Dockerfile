# Runs the Waddeln game server. Build context: the Gradle distribution (./gradlew :server:installDist)
# as install/ and the browser version (./gradlew :webApp:wasmJsBrowserDistribution) as web/, copied next
# to this file by deploy.sh.
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin waddeln
WORKDIR /app
COPY install/server/ /app/
# The browser version under /play/; part of the image, because the container is read-only.
COPY web/ /app/web/
ENV PORT=8765
ENV WEB_DIR=/app/web
EXPOSE 8765
# Never as root: a hole in the server must not give access to the Pi.
USER waddeln
CMD ["/app/bin/server"]
