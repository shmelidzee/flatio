import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
  java
  id("org.springframework.boot") version "3.2.12"
  id("io.spring.dependency-management") version "1.1.6"
}

group = "com.flatio"
version = "0.0.1-SNAPSHOT"

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

configurations {
  compileOnly {
    extendsFrom(configurations.annotationProcessor.get())
  }
}

repositories {
  mavenCentral()
}

val jsoupVersion = "1.17.2"
val mapstructVersion = "1.5.5.Final"
val resilience4jVersion = "2.2.0"
val testcontainersVersion = "1.19.7"
val springdocVersion = "2.4.0"
val logstashEncoderVersion = "7.4"
val telegramBotsVersion = "9.5.0"
val jjwtVersion = "0.12.6"

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-aop")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
  runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
  runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

  implementation("org.flywaydb:flyway-core")
  runtimeOnly("org.postgresql:postgresql")

  implementation("io.github.resilience4j:resilience4j-spring-boot3:$resilience4jVersion")
  implementation("io.github.resilience4j:resilience4j-retry:$resilience4jVersion")
  implementation("io.github.resilience4j:resilience4j-ratelimiter:$resilience4jVersion")

  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
  implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

  implementation("org.telegram:telegrambots-springboot-longpolling-starter:$telegramBotsVersion")
  implementation("org.telegram:telegrambots-springboot-webhook-starter:$telegramBotsVersion")
  implementation("org.telegram:telegrambots-client:$telegramBotsVersion")

  implementation("org.jsoup:jsoup:$jsoupVersion")
  implementation("org.mapstruct:mapstruct:$mapstructVersion")
  annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")

  compileOnly("org.projectlombok:lombok")
  annotationProcessor("org.projectlombok:lombok")
  annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")
}

dependencyManagement {
  imports {
    mavenBom("org.testcontainers:testcontainers-bom:$testcontainersVersion")
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
}

// Integration tests share the main 'test' source set — this task is an alias used in CI
tasks.register("integrationTest") {
  group = "verification"
  description = "Runs integration tests (Testcontainers-based tests live in the 'test' source set)"
  dependsOn("test")
}

tasks.named<BootJar>("bootJar") {
  archiveFileName.set("app.jar")
}
