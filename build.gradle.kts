import com.github.gradle.node.npm.task.NpmTask
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
  java
  id("org.springframework.boot") version "3.2.12"
  id("io.spring.dependency-management") version "1.1.6"
  id("com.github.node-gradle.node") version "7.0.2"
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

// Admin SPA build — see docs/architecture.md "Admin Interface" for the full pipeline.
val frontendDir = layout.projectDirectory.dir("frontend/admin")
val frontendDist = frontendDir.dir("dist")
val staticAdminDir = layout.projectDirectory.dir("src/main/resources/static/admin")

node {
  // CI (actions/setup-node), Docker (apk-installed Node) and local dev machines all provide
  // their own Node — the plugin does not need to download a separate copy.
  download.set(false)
  nodeProjectDir.set(frontendDir)
}

tasks.register<NpmTask>("npmCiFrontend") {
  group = "frontend"
  description = "Installs admin frontend dependencies via npm ci"
  npmCommand.set(listOf("ci"))
  inputs.file(frontendDir.file("package.json"))
  inputs.file(frontendDir.file("package-lock.json"))
  outputs.dir(frontendDir.dir("node_modules"))
}

tasks.register<NpmTask>("buildFrontend") {
  group = "frontend"
  description = "Builds the admin SPA (tsc + vite build)"
  dependsOn("npmCiFrontend")
  npmCommand.set(listOf("run", "build"))
  inputs.dir(frontendDir.dir("src"))
  inputs.file(frontendDir.file("index.html"))
  inputs.file(frontendDir.file("vite.config.ts"))
  inputs.file(frontendDir.file("tsconfig.json"))
  inputs.file(frontendDir.file("tailwind.config.js"))
  inputs.file(frontendDir.file("postcss.config.js"))
  outputs.dir(frontendDist)
}

tasks.register<Sync>("copyFrontendToStatic") {
  group = "frontend"
  description = "Copies the built admin SPA into src/main/resources/static/admin"
  dependsOn("buildFrontend")
  from(frontendDist)
  into(staticAdminDir)
}

tasks.named("processResources") {
  dependsOn("copyFrontendToStatic")
}

tasks.named<Delete>("clean") {
  delete(staticAdminDir)
}
