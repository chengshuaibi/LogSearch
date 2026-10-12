plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "org.atguigu"
version = "1.2.0"

repositories {
    // 1. 优先使用 JetBrains 官方仓库（IntelliJ 插件必须）
    intellijPlatform {
        defaultRepositories()
    }
    // 2. 中央仓库
    mavenCentral()
    // 3. 阿里云镜像
    maven {
        url = uri("https://maven.aliyun.com/repository/public")
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2023.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233.0"
            untilBuild = "253.*"
        }

        changeNotes = """
          Support versions up to 2025
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }
}