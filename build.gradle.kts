import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localPyCharm = file("/Applications/PyCharm.app")
        if (localPyCharm.exists()) {
            local(localPyCharm)
        } else {
            pycharm(providers.gradleProperty("platformVersion").get())
        }
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    patchPluginXml {
        sinceBuild.set(providers.gradleProperty("sinceBuild"))
    }
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        vendor {
            name = "Oto Abaiadze"
            email = "oabaiadze@gmail.com"
            url = "https://github.com/Obelisk121/tri-model-orchestrator"
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild")
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}
