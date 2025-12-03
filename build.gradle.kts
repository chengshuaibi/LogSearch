plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "org.atguigu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2023.2")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf(/* Plugin Dependencies */))

    // IMPORTANT: Ensure the Dameng driver JAR is bundled with the plugin
    // 假设 DmJdbcDriver18-8.1.3.62.jar 放在项目根目录的 lib 文件夹中
    // This tells the plugin to include the library inside the final JAR/ZIP
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("242.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
sourceSets {
    main {
        java.srcDirs("src/main/kotlin")
        resources.srcDirs("src/main/resources")
    }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
dependencies {
    // ----------------------------------------------------
    // 核心依赖
    // ----------------------------------------------------
    // 推荐：添加基础 POI 核心库，确保稳定解析
    implementation("org.apache.poi:poi:5.2.3")
    implementation("org.apache.poi:poi-ooxml:5.2.3")

    // (可选) 外部日志库
    implementation("org.apache.logging.log4j:log4j-core:2.20.0")

    // (可选) MySQL 驱动
    implementation("com.mysql:mysql-connector-j:8.2.0")

    // 新增：本地 Dameng 驱动依赖
    // Make sure to put the JAR in a 'lib' directory
    implementation(files("lib/DmJdbcDriver18-8.1.3.62.jar"))
}
