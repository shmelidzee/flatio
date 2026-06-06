FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

RUN addgroup -S flatio && adduser -S flatio -G flatio

COPY build/libs/app.jar app.jar

RUN chown flatio:flatio app.jar

USER flatio

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
