plugins {
    java
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Jackson for JSON parsing — the ONE external dependency we allow
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    // We'll change this as we build more components
    mainClass.set("gateway.mock.MockUpstream")
}

tasks.test {
    useJUnitPlatform()
}

// Convenience task to run the mock upstream
tasks.register<JavaExec>("mockUpstream") {
    mainClass.set("gateway.mock.MockUpstream")
    classpath = sourceSets["main"].runtimeClasspath
}

// Convenience task to run the gateway reverse proxy
tasks.register<JavaExec>("gateway") {
    mainClass.set("gateway.proxy.GatewayServer")
    classpath = sourceSets["main"].runtimeClasspath
}

// Convenience task to run the mock fallback upstream
tasks.register<JavaExec>("mockFallback") {
    mainClass.set("gateway.mock.MockFallbackUpstream")
    classpath = sourceSets["main"].runtimeClasspath
}

// Convenience task to run the load test
tasks.register<JavaExec>("loadTest") {
    mainClass.set("gateway.GatewayLoadTest")
    classpath = sourceSets["test"].runtimeClasspath
}



