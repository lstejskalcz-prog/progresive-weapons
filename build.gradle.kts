plugins {
    java
}

group = "cz.tierweapons"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Uprav verzi podle Paper docs pro svoji verzi serveru (26.3)
    compileOnly("io.papermc.paper:paper-api:26.3.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.jar {
    archiveFileName.set("TierWeapons.jar")
}
