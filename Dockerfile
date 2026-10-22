FROM node:24-slim AS client
WORKDIR /client
COPY client/package.json ./
RUN npm install --no-audit --no-fund
COPY client ./
RUN npm run build

FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/site.jar ./
COPY server.json not-found.html ./
COPY --from=client /client/dist ./site
ENV PORT=8080 DATA_DIR=/data JAVA_TOOL_OPTIONS="-Xmx256m -Xss512k -XX:+UseSerialGC"
VOLUME /data
EXPOSE 8080
CMD ["java", "-jar", "site.jar"]
