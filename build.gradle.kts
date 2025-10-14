
plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.gradleup.nmcp").version("0.0.8")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.jeff-media.com/public/")
    maven("https://repo.nexomc.com/public/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper)
    compileOnly(libs.folia)
    compileOnly(libs.jetbrains.annotations)

    testImplementation(libs.junit.junit)
    testImplementation(libs.slf4j)
}

group = "com.nexomc"
version = "1.1.1"
description = "CustomBlockData"
java.sourceCompatibility = JavaVersion.VERSION_1_8
java.disableAutoTargetJvm()

java {
    withSourcesJar()
    withJavadocJar()

    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
}

publishing {
    repositories {
        maven {
            val repo = "https://repo.nexomc.com/"
            val isSnapshot = System.getenv("IS_SNAPSHOT") == "true"
            val url = if (isSnapshot) repo + "snapshots" else repo + "releases"
            setUrl(url)
            credentials {
                username = project.findProperty("mineinabyssMavenUsername") as String?
                password = project.findProperty("mineinabyssMavenPassword") as String?
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = rootProject.group.toString()
            artifactId = rootProject.name
            version = rootProject.version.toString()
        }
    }
}
