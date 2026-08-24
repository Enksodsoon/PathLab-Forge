import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    application
}

group = "org.pathlab"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.18.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "org.pathlab.forge.ForgeApp"
    val viewerOrigin = providers.gradleProperty("pathlab.forge.viewer.defaultOrigin")
        .orElse(providers.environmentVariable("PATHLAB_FORGE_VIEWER_DEFAULT_ORIGIN"))
    applicationDefaultJvmArgs = listOf("-Xmx512m") + viewerOrigin.orNull
        ?.let { listOf("-Dpathlab.forge.viewer.defaultOrigin=$it") }
        .orEmpty()
}

tasks.register("productionDist") {
    group = "distribution"
    description = "Builds a release distribution with an explicit official Viewer origin."
    dependsOn(tasks.installDist)
    doFirst {
        val configured = providers.gradleProperty("pathlab.forge.viewer.defaultOrigin")
            .orElse(providers.environmentVariable("PATHLAB_FORGE_VIEWER_DEFAULT_ORIGIN"))
            .orNull
        require(!configured.isNullOrBlank()) {
            "Production packaging requires pathlab.forge.viewer.defaultOrigin"
        }
        require(configured.startsWith("https://")) {
            "Production Viewer origin must use HTTPS"
        }
    }
}

val pnpmCommand = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "pnpm.cmd" else "pnpm"
val codexNodeBin = file(
    "${System.getProperty("user.home")}/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin")
val frontendPath = if (codexNodeBin.isDirectory) {
    codexNodeBin.absolutePath + File.pathSeparator + System.getenv("PATH")
} else {
    System.getenv("PATH")
}

val frontendInstall = tasks.register<Exec>("frontendInstall") {
    workingDir = file("frontend")
    commandLine(pnpmCommand, "install", "--frozen-lockfile")
    environment("PATH", frontendPath)
    inputs.files("frontend/package.json", "frontend/pnpm-lock.yaml")
    doNotTrackState("pnpm's Windows junction-based node_modules tree is not snapshot-safe")
}

val frontendBuild = tasks.register<Exec>("frontendBuild") {
    dependsOn(frontendInstall)
    workingDir = file("frontend")
    commandLine(pnpmCommand, "run", "build")
    environment("PATH", frontendPath)
    inputs.dir("frontend/src")
    inputs.files(
        "frontend/index.html",
        "frontend/package.json",
        "frontend/pnpm-lock.yaml",
        "frontend/tsconfig.json",
        "frontend/vite.config.ts")
    outputs.dir(layout.buildDirectory.dir("frontend/web"))
}

tasks.processResources {
    dependsOn(frontendBuild)
    exclude("web/app.html", "web/app.css", "web/app.js")
    from(layout.buildDirectory.dir("frontend/web")) {
        into("web")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("file.encoding", "UTF-8")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("user.timezone", "UTC")
    systemProperty("pathlab.forge.resourceGovernor.enabled", "false")
    systemProperty(
        "pathlab.forge.runtime.processors",
        System.getProperty("pathlab.forge.runtime.processors", "6"))
    systemProperty(
        "pathlab.forge.runtime.memoryBytes",
        System.getProperty(
            "pathlab.forge.runtime.memoryBytes",
            (8L * 1024 * 1024 * 1024).toString()))
    listOf(
        "pathlab.forge.test.ome",
        "pathlab.forge.test.ome.width",
        "pathlab.forge.test.ome.height",
        "pathlab.forge.test.fullDzi",
        "pathlab.forge.test.heSource",
        "pathlab.forge.test.runtimeRoot",
    ).forEach { name ->
        System.getProperty(name)?.let { value -> systemProperty(name, value) }
    }
    reports.junitXml.mergeReruns = false
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register<JavaExec>("brightfieldQualification") {
    group = "verification"
    description = "Runs synthetic-only cell and IHC pre-qualification checks."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.pathlab.forge.evidence.BrightfieldQualificationHarness"
    val report = providers.gradleProperty("pathlab.qualification.output")
        .orElse(layout.buildDirectory.file("qualification/brightfield-report.json").map { it.asFile.absolutePath })
    args(
        "--packs", file("src/main/resources/evidence-packs").absolutePath,
        "--output", report.get())
}

tasks.register<JavaExec>("cellInstanceQualification") {
    group = "verification"
    description = "Runs the frozen held-out cell-instance qualification evaluator."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.pathlab.forge.evidence.CellInstanceQualificationEvaluator"
    maxHeapSize = "768m"
    val cohort = providers.gradleProperty("pathlab.cell.cohort")
    val cohortSha = providers.gradleProperty("pathlab.cell.cohortSha256")
    val report = providers.gradleProperty("pathlab.cell.output")
        .orElse(layout.buildDirectory.file("qualification/cell-instance-metrics.json")
            .map { it.asFile.absolutePath })
    doFirst { setArgs(listOf(cohort.get(), cohortSha.get(), report.get())) }
}

val installVersionedRuntime = tasks.register<Sync>("installVersionedRuntime") {
    dependsOn(tasks.installDist)
    val localAppData = providers.environmentVariable("LOCALAPPDATA")
        .orElse("${System.getProperty("user.home")}/AppData/Local")
    val runtimeVersion = providers.provider {
        System.getenv("PATHLAB_FORGE_RUNTIME_VERSION")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
            ?: project.version.toString()
    }
    from(layout.buildDirectory.dir("install/${project.name}"))
    into(localAppData.zip(runtimeVersion) { root, release ->
        file("$root/PathLab Forge/runtime/app/$release")
    })
    doLast {
        val pointer = file("${localAppData.get()}/PathLab Forge/runtime/current.txt")
        pointer.parentFile.mkdirs()
        val partial = file("${pointer.absolutePath}.partial")
        partial.writeText(runtimeVersion.get() + System.lineSeparator())
        Files.move(
            partial.toPath(),
            pointer.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
        logger.lifecycle("Installed PathLab Forge runtime ${runtimeVersion.get()} at ${destinationDir}")
    }
}
