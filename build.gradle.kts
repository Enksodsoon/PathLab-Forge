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
    implementation("org.apache.poi:poi:5.5.1")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val viewerOrigin = providers.gradleProperty("pathlab.forge.viewer.defaultOrigin")
    .orElse(providers.environmentVariable("PATHLAB_FORGE_VIEWER_DEFAULT_ORIGIN"))

application {
    mainClass = "org.pathlab.forge.ForgeApp"
    applicationDefaultJvmArgs = listOf("-Xmx512m", "--enable-native-access=ALL-UNNAMED") + viewerOrigin.orNull
        ?.let { listOf("-Dpathlab.forge.viewer.defaultOrigin=$it") }
        .orEmpty()
}

tasks.register("productionDist") {
    group = "distribution"
    description = "Builds a release distribution with an explicit official Viewer origin."
    dependsOn("verifyReaderRuntimeBundle", tasks.installDist)
}

tasks.register<Exec>("verifyReaderRuntimeBundle") {
    group = "verification"
    description = "Fail-closed verification of licensed, pinned Bio-Formats and libvips artifacts."
    val runtimeRoot = providers.gradleProperty("pathlab.forge.readerRuntimeRoot").orElse("PENDING_REVIEW")
    commandLine("powershell", "-NoProfile", "-File",
        layout.projectDirectory.file("scripts/verify-reader-runtime.ps1").asFile,
        "-RuntimeRoot", runtimeRoot.get())
    inputs.files("reader-runtime.lock.properties", "scripts/verify-reader-runtime.ps1")
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
        "pathlab.forge.test.mds",
        "pathlab.forge.test.sdpc",
        "pathlab.forge.sdpcRuntime",
        "pathlab.forge.test.isyntax",
        "pathlab.forge.isyntaxRuntime",
        "pathlab.forge.isyntaxPython",
        "pathlab.forge.test.svsRoots",
        "pathlab.forge.test.svsReport",
        "pathlab.forge.test.omeMetadataTarget",
    ).forEach { name ->
        System.getProperty(name)?.let { value -> systemProperty(name, value) }
    }
    reports.junitXml.mergeReruns = false
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
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

val internalReaderRoot = layout.buildDirectory.dir("internal-reader-dist")

val cleanInternalReaderDist = tasks.register<Delete>("cleanInternalReaderDist") {
    delete(internalReaderRoot)
}

val stageInternalReaderApp = tasks.register<Exec>("stageInternalReaderApp") {
    group = "distribution"
    description = "Builds a Windows application image with its own Java runtime."
    dependsOn(cleanInternalReaderDist, tasks.installDist)
    val jpackage = file("${System.getProperty("java.home")}/bin/jpackage.exe")
    val input = layout.buildDirectory.dir("install/${project.name}/lib").get().asFile
    inputs.dir(input)
    doNotTrackState("jpackage requires its application-image destination not to exist")
    commandLine(
        jpackage,
        "--type", "app-image",
        "--input", input,
        "--dest", internalReaderRoot.get().asFile,
        "--name", "PathLab Forge",
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", "org.pathlab.forge.ForgeApp",
        "--add-modules", "ALL-MODULE-PATH",
        "--java-options", "-Xmx512m",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--win-console")
}

val stageInternalReaderChildJvm = tasks.register<Copy>("stageInternalReaderChildJvm") {
    group = "distribution"
    description = "Adds the matching Java launcher required by the contained Bio-Formats child process."
    dependsOn(stageInternalReaderApp)
    from(file("${System.getProperty("java.home")}/bin/java.exe"))
    into(internalReaderRoot.map { it.dir("PathLab Forge/runtime/bin") })
}

val assembleInternalReaderRuntime = tasks.register<JavaExec>("assembleInternalReaderRuntime") {
    group = "distribution"
    description = "Copies an owner-supplied reader runtime into an internal validation package."
    dependsOn(stageInternalReaderChildJvm, tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.pathlab.forge.runtime.ReaderRuntimeAssembler"
    val source = providers.gradleProperty("pathlab.forge.readerRuntimeRoot")
    inputs.dir(source)
    outputs.dir(internalReaderRoot.map { it.dir("PathLab Forge/reader-data/runtime") })
    args(source.get(), internalReaderRoot.get().dir("PathLab Forge/reader-data").asFile.absolutePath,
        "INTERNAL", "windows-x86_64")
}

tasks.register("internalReaderDist") {
    group = "distribution"
    description = "Builds a NON_REDISTRIBUTABLE Windows package with owner-supplied readers."
    dependsOn(assembleInternalReaderRuntime)
    outputs.dir(internalReaderRoot)
}

val productionReaderRoot = layout.buildDirectory.dir("production-reader-dist")

val cleanProductionReaderDist = tasks.register<Delete>("cleanProductionReaderDist") {
    delete(productionReaderRoot)
}

val stageProductionReaderApp = tasks.register<Exec>("stageProductionReaderApp") {
    group = "distribution"
    description = "Builds the approved Windows application image with its own Java runtime."
    dependsOn(cleanProductionReaderDist, tasks.installDist, "verifyReaderRuntimeBundle")
    val jpackage = file("${System.getProperty("java.home")}/bin/jpackage.exe")
    val input = layout.buildDirectory.dir("install/${project.name}/lib").get().asFile
    val officialOrigin = viewerOrigin.orNull
    require(!officialOrigin.isNullOrBlank()) {
        "Production packaging requires pathlab.forge.viewer.defaultOrigin"
    }
    require(officialOrigin.startsWith("https://")) {
        "Production Viewer origin must use HTTPS"
    }
    inputs.dir(input)
    doNotTrackState("jpackage requires its application-image destination not to exist")
    commandLine(
        jpackage,
        "--type", "app-image",
        "--input", input,
        "--dest", productionReaderRoot.get().asFile,
        "--name", "PathLab Forge",
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", "org.pathlab.forge.ForgeApp",
        "--add-modules", "ALL-MODULE-PATH",
        "--java-options", "-Xmx512m",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Dpathlab.forge.viewer.defaultOrigin=$officialOrigin")
}

val stageProductionReaderChildJvm = tasks.register<Copy>("stageProductionReaderChildJvm") {
    group = "distribution"
    description = "Adds the matching Java launcher for the approved Bio-Formats child process."
    dependsOn(stageProductionReaderApp)
    from(file("${System.getProperty("java.home")}/bin/java.exe"))
    into(productionReaderRoot.map { it.dir("PathLab Forge/runtime/bin") })
}

val assembleProductionReaderRuntime = tasks.register<JavaExec>("assembleProductionReaderRuntime") {
    group = "distribution"
    description = "Assembles an approved, hash-locked production reader runtime."
    dependsOn(stageProductionReaderChildJvm, tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.pathlab.forge.runtime.ReaderRuntimeAssembler"
    val source = providers.gradleProperty("pathlab.forge.readerRuntimeRoot")
    inputs.dir(source)
    outputs.dir(productionReaderRoot.map { it.dir("PathLab Forge/reader-data/runtime") })
    args(source.get(), productionReaderRoot.get().dir("PathLab Forge/reader-data").asFile.absolutePath,
        "PRODUCTION", "windows-x86_64")
}

tasks.named("productionDist") {
    setDependsOn(listOf(assembleProductionReaderRuntime))
    outputs.dir(productionReaderRoot)
}
