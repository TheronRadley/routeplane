rootProject.name = "routeplane"

include("app", "fault-upstream", "benchmarks")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
