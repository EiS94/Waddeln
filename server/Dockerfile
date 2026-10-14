# Runs the Waddeln game server. Build context: the Gradle distribution (./gradlew :server:installDist),
# copied next to this file by deploy.sh.
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin waddeln
WORKDIR /app
COPY install/server/ /app/
ENV PORT=8765
EXPOSE 8765
# Never as root: a hole in the server must not give access to the Pi.
USER waddeln
CMD ["/app/bin/server"]
