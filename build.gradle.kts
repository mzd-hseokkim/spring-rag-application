plugins {
    id("org.sonarqube") version "7.2.3.7755"
}

sonar {
    properties {
        property("sonar.projectKey", "spring-rag-server")
        property("sonar.host.url", "http://localhost:9000")
    }
}

tasks.register<Exec>("pnpmInstall") {
    group = "frontend"
    description = "Install frontend dependencies"
    workingDir = file("frontend")
    commandLine("pnpm", "install")
}

tasks.register<Exec>("frontendBuild") {
    group = "frontend"
    description = "Build frontend application"
    workingDir = file("frontend")
    commandLine("pnpm", "run", "build")
    dependsOn("pnpmInstall")
}

tasks.register<Exec>("frontendDev") {
    group = "frontend"
    description = "Start frontend dev server"
    workingDir = file("frontend")
    commandLine("pnpm", "run", "dev")
}

tasks.register("buildAll") {
    group = "build"
    description = "Build both backend and frontend"
    dependsOn(":backend:build", "frontendBuild")
}
