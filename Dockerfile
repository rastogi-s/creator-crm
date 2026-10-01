# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

# ---- run ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 crm && mkdir /data && chown crm /data
COPY --from=build /src/target/creator-manager-*.jar /app/app.jar
USER crm
ENV CRM_DATA_DIR=/data \
    HOST=0.0.0.0 \
    PORT=8080
EXPOSE 8080
VOLUME /data
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
