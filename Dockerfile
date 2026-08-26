FROM openjdk:17-jdk-slim as build

WORKDIR /app

COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline

COPY src ./src
RUN ./mvnw package -DskipTests

FROM openjdk:17-jdk-slim

WORKDIR /app

COPY --from=build /app/target/auralink-backend-0.0.1-SNAPSHOT.jar app.jar

# 创建必要的目录
RUN mkdir -p ./temp_uploads
RUN mkdir -p ./public/audios

EXPOSE 5000

ENTRYPOINT ["java", "-jar", "app.jar"]