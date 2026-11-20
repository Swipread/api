# Image de production : JAR exécutable sur un JRE, utilisateur non root.
# Les tests ne tournent pas ici (CI) : docker build -t swipread-api .

FROM eclipse-temurin:25-jdk AS build
# unzip : le wrapper Maven télécharge alors l'archive .zip, comme la CI, et non le .tar.gz (déjà vu en 404 sur
# Maven Central). Seulement dans l'étape de build : l'image finale n'en a pas besoin.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Dépendances dans leur propre couche : modifier le code ne les retélécharge pas
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# Version de l'application, fournie par la release (sinon celle du pom)
ARG VERSION
RUN ./mvnw -B -q -DskipTests ${VERSION:+-Drevision=$VERSION} package && cp target/api-*.jar app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system swipread && useradd --system --gid swipread swipread
COPY --from=build /app/app.jar app.jar
USER swipread
EXPOSE 8080
# Tas à 75 % de la limite mémoire du conteneur ; en cas d'OutOfMemoryError, sortir pour être redémarré
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
