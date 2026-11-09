
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
    // Add JPA plugin for Kotlin
    kotlin("plugin.jpa") version "2.3.21"
}

group = "dev.orestegabo"
version = "0.0.1-SNAPSHOT"

val sequoReleaseId = providers.environmentVariable("SEQUO_RELEASE_ID").getOrElse("local-unidentified")
val sequoSourceRevision = providers.environmentVariable("SEQUO_SOURCE_REVISION").getOrElse("unidentified")
require(sequoReleaseId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"))) { "Invalid public release identifier." }
require(sequoSourceRevision == "unidentified" || sequoSourceRevision.matches(Regex("[a-f0-9]{40,64}"))) { "Invalid source revision." }
springBoot {
    buildInfo {
        properties {
            additional.set(mapOf("releaseId" to sequoReleaseId, "revision" to sequoSourceRevision))
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    
    implementation("com.google.zxing:core:3.3.3")

    // Database
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Social Token Verification
    implementation("com.google.api-client:google-api-client:2.7.0")
    implementation("com.google.http-client:google-http-client-gson:1.44.1")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		jvmTarget.set(JvmTarget.JVM_21)
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
    maxHeapSize = "1536m"
    forkEvery = 100
}
