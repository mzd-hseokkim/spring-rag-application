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
