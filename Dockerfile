FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src/ src/
RUN mvn package -q -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/maven-fetcher-1.0.0.jar app.jar

EXPOSE 8080

ENV MAVEN_FETCHER_DB_URL=""
ENV MAVEN_FETCHER_DB_USER="maven"
ENV MAVEN_FETCHER_DB_PASSWORD="maven"
ENV PORT=8080

CMD ["sh", "-c", "java -jar app.jar --serve --db-url \"$MAVEN_FETCHER_DB_URL\" --db-user \"$MAVEN_FETCHER_DB_USER\" --db-password \"$MAVEN_FETCHER_DB_PASSWORD\" --port \"$PORT\""]
