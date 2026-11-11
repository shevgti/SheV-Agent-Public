pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx publishes the Android AAR and JVM bindings as GitHub release assets.
        ivy {
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout { artifact("[revision]/[artifact]-2.0.1.[ext]") }
            metadataSources { artifact() }
        }
    }
}
rootProject.name = "SheVAgent"
include(":app", ":shevchat")
