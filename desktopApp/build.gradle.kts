import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import javax.imageio.ImageIO
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
}

val appVersion = "1.0.0"

compose.desktop {
    application {
        mainClass = "com.omnireader.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "OmniReader"
            packageVersion = appVersion
            description = "EPUB reader with library, author collections and text-to-speech"
            vendor = "Sandesh"
            copyright = "Copyright (c) 2026 Sandesh"
            modules("java.instrument", "java.management", "jdk.unsupported", "java.net.http", "java.sql")
        }
    }
}

val appImageSourceDir = layout.buildDirectory.dir("compose/binaries/main/app/OmniReader")
val stageDir = layout.buildDirectory.dir("packaging/stage")
val rpmTopDir = layout.buildDirectory.dir("packaging/rpm")
val nativeOutputDir = layout.buildDirectory.dir("compose/binaries/main/native")
val desktopEntryContent = """
    [Desktop Entry]
    Type=Application
    Name=OmniReader
    Comment=EPUB reader with library, author collections and text-to-speech
    Exec=OmniReader
    Icon=omnireader
    Categories=Office;Reader;Education;
    Terminal=false
    StartupWMClass=OmniReader
    Keywords=epub;reader;book;tts;
""".trimIndent() + "\n"

val appRunContent = """
    #!/bin/sh
    HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
    export PATH="${'$'}HERE/OmniReader/bin:${'$'}PATH"
    exec "${'$'}HERE/OmniReader/bin/OmniReader" "${'$'}@"
""".trimIndent() + "\n"

val debDependencies = listOf(
    "libc6", "libgcc1", "libstdc++6", "zlib1g",
    "libx11-6", "libxext6", "libxrender1", "libxtst6",
    "libfreetype6", "libfontconfig1", "libgl1"
)

fun stagePackage() {
    val stage = stageDir.get().asFile
    stage.deleteRecursively()

    val optTarget = File(stage, "opt/OmniReader")
    optTarget.parentFile.mkdirs()
    copyTreePreservingPermissions(appImageSourceDir.get().asFile, optTarget)

    val iconFile = File(stage, "usr/share/icons/hicolor/256x256/apps/omnireader.png")
    iconFile.parentFile.mkdirs()
    writeAppIcon(iconFile)

    val desktopFile = File(stage, "usr/share/applications/omnireader.desktop")
    desktopFile.parentFile.mkdirs()
    desktopFile.writeText(desktopEntryContent)

    val launcher = File(stage, "usr/bin/OmniReader")
    launcher.parentFile.mkdirs()
    launcher.delete()
    Files.createSymbolicLink(
        launcher.toPath(),
        Paths.get("../opt/OmniReader/bin/OmniReader")
    )
}

val packageDeb by tasks.registering {
    group = "compose desktop"
    description = "Builds a .deb package using dpkg-deb."
    dependsOn("createDistributable")
    inputs.dir(appImageSourceDir)
    outputs.dir(nativeOutputDir)

    doLast {
        val output = nativeOutputDir.get().asFile
        output.mkdirs()
        stagePackage()

        val stage = stageDir.get().asFile
        val installedSizeKib = stage.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() } / 1024

        val controlDir = File(stage, "DEBIAN")
        controlDir.mkdirs()
        File(controlDir, "control").writeText(
            """
            Package: omnireader
            Version: $appVersion
            Section: books
            Priority: optional
            Architecture: amd64
            Installed-Size: $installedSizeKib
            Maintainer: OmniReader <omnireader@localhost>
            Depends: ${debDependencies.joinToString(", ")}
            Homepage: https://localhost/omnireader
            Description: EPUB reader
             OmniReader is a desktop EPUB reader with a scanned library, author
             collections, reading progress and text-to-speech playback.
            """.trimIndent() + "\n"
        )
        File(controlDir, "md5sums").writeText(
            stage.walkTopDown()
                .filter { it.isFile && !it.toPath().startsWith(controlDir.toPath()) }
                .sortedBy { it.relativeTo(stage).invariantSeparatorsPath }
                .joinToString("\n") { file ->
                    val digest = MessageDigest.getInstance("MD5")
                        .digest(file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    "${digest}  ${file.relativeTo(stage).invariantSeparatorsPath.removePrefix("./")}"
                } + "\n"
        )

        val debFile = File(output, "omnireader_${appVersion}_amd64.deb")
        val result = exec {
            commandLine("dpkg-deb", "--root-owner-group", "--build", stage.absolutePath, debFile.absolutePath)
        }
        check(result.exitValue == 0) { "dpkg-deb failed" }
        logger.lifecycle("deb: ${debFile.absolutePath}")
    }
}

val packageRpm by tasks.registering {
    group = "compose desktop"
    description = "Builds an .rpm package using rpmbuild."
    dependsOn("createDistributable")
    inputs.dir(appImageSourceDir)
    inputs.file(file("packaging/omnireader.spec"))
    outputs.dir(nativeOutputDir)

    doLast {
        val output = nativeOutputDir.get().asFile
        output.mkdirs()
        stagePackage()

        val top = rpmTopDir.get().asFile
        top.deleteRecursively()
        top.mkdirs()
        val rpmOut = File(top, "rpmout")
        rpmOut.mkdirs()

        val spec = file("packaging/omnireader.spec")
        val result = exec {
            workingDir = projectDir
            commandLine(
                "rpmbuild", "-bb", spec.absolutePath,
                "--define", "_topdir ${top.absolutePath}",
                "--define", "_rpmdir ${rpmOut.absolutePath}",
                "--define", "_omni_version $appVersion",
                "--define", "_omni_stage ${stageDir.get().asFile.absolutePath}",
                "--define", "_build_id_links none",
                "--nodeps"
            )
        }
        check(result.exitValue == 0) { "rpmbuild failed" }

        val built = rpmOut.walkTopDown().filter { it.isFile && it.extension == "rpm" }.toList()
        check(built.isNotEmpty()) { "rpmbuild produced no rpm" }
        built.forEach { rpm ->
            val target = File(output, rpm.name)
            target.delete()
            rpm.copyTo(target)
            logger.lifecycle("rpm: ${target.absolutePath}")
        }
    }
}

val packageAppImage by tasks.registering {
    group = "compose desktop"
    description = "Builds a portable AppImage using mksquashfs and the bundled type-2 runtime."
    dependsOn("createDistributable")
    inputs.dir(appImageSourceDir)
    inputs.file(file("tools/appimage-runtime"))
    outputs.dir(nativeOutputDir)

    doLast {
        val output = nativeOutputDir.get().asFile
        output.mkdirs()

        val runtime = file("tools/appimage-runtime")
        val mksquashfs = System.getenv("MKSQUASHFS") ?: "mksquashfs"
        check(runtime.isFile) { "AppImage runtime missing at ${runtime.absolutePath}" }

        val appDir = File(nativeOutputDir.get().asFile, "appimage-root")
        appDir.deleteRecursively()
        appDir.mkdirs()

        val appRun = File(appDir, "AppRun")
        appRun.writeText(appRunContent)
        appRun.setReadable(true, false)
        appRun.setWritable(true, false)
        appRun.setExecutable(true, false)

        val entryFile = File(appDir, "omnireader.desktop")
        entryFile.writeText(desktopEntryContent)
        entryFile.setReadable(true, false)
        entryFile.setWritable(true, false)
        entryFile.setExecutable(false, false)

        val iconFile = File(appDir, "usr/share/icons/hicolor/256x256/apps/omnireader.png")
        iconFile.parentFile.mkdirs()
        writeAppIcon(iconFile)

        copyTreePreservingPermissions(appImageSourceDir.get().asFile, File(appDir, "OmniReader"))

        val squashfs = File(nativeOutputDir.get().asFile, "appimage-root.squashfs")
        squashfs.delete()
        val squashResult = exec {
            commandLine(
                mksquashfs, appDir.absolutePath, squashfs.absolutePath,
                "-root-owned", "-noappend", "-no-progress", "-comp", "gzip", "-b", "131072"
            )
        }
        check(squashResult.exitValue == 0) { "mksquashfs failed" }

        val target = File(output, "OmniReader-$appVersion-x86_64.AppImage")
        target.delete()
        target.outputStream().buffered().use { out ->
            runtime.inputStream().use { it.copyTo(out) }
            squashfs.inputStream().use { it.copyTo(out) }
        }
        target.setExecutable(true)

        appDir.deleteRecursively()
        squashfs.delete()

        val header = target.readBytes().copyOfRange(0, 16)
        check(header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte()) { "AppImage is not an ELF runtime" }
        check(header[8] == 'A'.code.toByte() && header[9] == 'I'.code.toByte()) { "AppImage runtime magic missing" }
        val squashMagic = target.readBytes().let { bytes ->
            bytes.copyOfRange(runtime.length().toInt(), runtime.length().toInt() + 4).decodeToString()
        }
        check(squashMagic == "hsqs") { "Squashfs payload missing at expected offset" }
        logger.lifecycle("appimage: ${target.absolutePath} (${target.length() / 1024 / 1024} MiB)")
    }
}

val packageAll by tasks.registering {
    group = "compose desktop"
    description = "Builds deb, rpm and AppImage packages."
    dependsOn(packageDeb, packageRpm, packageAppImage)
}

fun copyTreePreservingPermissions(source: File, target: File) {
    target.deleteRecursively()
    target.mkdirs()

    source.walkTopDown().forEach { src ->
        val dest = File(target, src.relativeTo(source).path)
        if (src.isDirectory) {
            dest.mkdirs()
        } else {
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            dest.setReadable(true, false)
            dest.setWritable(true, false)
            dest.setExecutable(src.canExecute(), false)
        }
    }
}

fun writeAppIcon(target: File) {
    val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.color = Color(0x43, 0x56, 0xE8)
    graphics.fillRoundRect(0, 0, 256, 256, 56, 56)
    graphics.color = Color.WHITE
    graphics.font = Font("SansSerif", Font.BOLD, 104)
    val metrics = graphics.fontMetrics
    val text = "OR"
    val x = (256 - metrics.stringWidth(text)) / 2
    val y = (256 - metrics.height) / 2 + metrics.ascent
    graphics.drawString(text, x, y)
    graphics.dispose()
    ImageIO.write(image, "png", target)
}
