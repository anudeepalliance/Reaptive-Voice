import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.json)

    implementation(libs.compose.uiToolingPreview)
    testImplementation(libs.junit)
}

val dictationHelperApp = rootProject.layout.projectDirectory.dir("native/macos/Rvoice.app")
val dictationHelperExecutable = dictationHelperApp.file("Contents/MacOS/ReaptiveVoiceDictationHelper")
val dictationHelperSource = rootProject.layout.projectDirectory.file("native/macos/GlobalDictationHelper.swift")
val dictationHelperInfoPlist = rootProject.layout.projectDirectory.file("native/macos/HelperInfo.plist")
val dictationHelperLauncherSource = rootProject.layout.projectDirectory.file("native/macos/launch-dictation-helper.c")
val dictationHelperLauncher = rootProject.layout.projectDirectory.file("native/macos/ReaptiveVoiceLauncher")

tasks.register<Exec>("compileDictationHelper") {
    inputs.file(dictationHelperSource)
    inputs.file(dictationHelperInfoPlist)
    outputs.file(dictationHelperExecutable)
    val appPath = dictationHelperApp.asFile.absolutePath
    val executablePath = dictationHelperExecutable.asFile.absolutePath
    val sourcePath = dictationHelperSource.asFile.absolutePath
    val infoPlistPath = dictationHelperInfoPlist.asFile.absolutePath
    commandLine(
        "bash",
        "-c",
        """
        if /usr/bin/codesign --verify --strict '$appPath' 2>/dev/null && [ '$executablePath' -nt '$sourcePath' ]; then
          exit 0
        fi
        mkdir -p '$appPath/Contents/MacOS' \
        && cp '$infoPlistPath' '$appPath/Contents/Info.plist' \
        && /usr/bin/swiftc -parse-as-library -O -o '$executablePath' '$sourcePath' -framework Cocoa -framework Carbon -framework ApplicationServices -framework AVFoundation \
        && /usr/bin/codesign -s - --force --identifier com.reaptivevoice.dictation-helper '$appPath'
        """.trimIndent(),
    )
}

val microphoneRecorderBinary = rootProject.layout.projectDirectory.file("native/macos/ReaptiveVoiceMicrophone")
val microphoneRecorderSource = rootProject.layout.projectDirectory.file("native/macos/MicrophoneRecorder.swift")
val microphoneRecorderInfoPlist = rootProject.layout.projectDirectory.file("native/macos/MicrophoneInfo.plist")

tasks.register<Exec>("compileMicrophoneRecorder") {
    inputs.file(microphoneRecorderSource)
    inputs.file(microphoneRecorderInfoPlist)
    inputs.file(dictationHelperLauncherSource)
    outputs.file(microphoneRecorderBinary)
    outputs.file(dictationHelperLauncher)
    val recorderPath = microphoneRecorderBinary.asFile.absolutePath
    val recorderSourcePath = microphoneRecorderSource.asFile.absolutePath
    val infoPlistPath = microphoneRecorderInfoPlist.asFile.absolutePath
    val launcherSourcePath = dictationHelperLauncherSource.asFile.absolutePath
    val launcherPath = dictationHelperLauncher.asFile.absolutePath
    commandLine(
        "bash",
        "-c",
        """
        if /usr/bin/codesign --verify --strict '$launcherPath' 2>/dev/null && [ '$launcherPath' -nt '$launcherSourcePath' ] \
          && /usr/bin/codesign --verify --strict '$recorderPath' 2>/dev/null && [ '$recorderPath' -nt '$recorderSourcePath' ]; then
          exit 0
        fi
        /usr/bin/cc -O2 -o '$launcherPath' '$launcherSourcePath' \
        && /usr/bin/codesign -s - --force --identifier com.reaptivevoice.dictation-launcher '$launcherPath' \
        && /usr/bin/swiftc -parse-as-library -O -o '$recorderPath' '$recorderSourcePath' -framework AVFoundation -framework AVFAudio -framework AudioToolbox -framework CoreAudio \
          -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker '$infoPlistPath' \
        && /usr/bin/codesign -s - --force --identifier com.reaptivevoice.microphone '$recorderPath'
        """.trimIndent(),
    )
}

val speechSpeakerBinary = rootProject.layout.projectDirectory.file("native/macos/RvoiceSpeaker")
val speechSpeakerSource = rootProject.layout.projectDirectory.file("native/macos/rvoice-speaker.cpp")

/** Builds the program that keeps LFM3-Audio-2B loaded and reads text out loud. It links against Homebrew's llama.cpp. */
tasks.register<Exec>("compileSpeechSpeaker") {
    inputs.file(speechSpeakerSource)
    outputs.file(speechSpeakerBinary)
    val speakerPath = speechSpeakerBinary.asFile.absolutePath
    val speakerSourcePath = speechSpeakerSource.asFile.absolutePath
    commandLine(
        "bash",
        "-c",
        """
        if /usr/bin/codesign --verify --strict '$speakerPath' 2>/dev/null && [ '$speakerPath' -nt '$speakerSourcePath' ]; then
          exit 0
        fi
        LLAMA_PREFIX="$(brew --prefix llama.cpp)" && GGML_PREFIX="$(brew --prefix ggml)" \
        && /usr/bin/c++ -std=c++17 -O2 -o '$speakerPath' '$speakerSourcePath' \
          -I"${'$'}LLAMA_PREFIX/include" -I"${'$'}GGML_PREFIX/include" -L"${'$'}LLAMA_PREFIX/lib" -L"${'$'}GGML_PREFIX/lib" \
          -lllama -lmtmd -lggml -lggml-base -framework AudioToolbox -framework CoreFoundation \
          -Wl,-rpath,"${'$'}LLAMA_PREFIX/lib" -Wl,-rpath,"${'$'}GGML_PREFIX/lib" \
        && /usr/bin/codesign -s - --force --identifier com.reaptivevoice.speaker '$speakerPath'
        """.trimIndent(),
    )
}

tasks.matching { task ->
    task.name == "run"
}.configureEach {
    dependsOn("compileDictationHelper")
    dependsOn("compileMicrophoneRecorder")
    dependsOn("compileSpeechSpeaker")
    if (this is JavaExec) {
        workingDir = rootProject.projectDir
        jvmArgs(
            "-Dapple.awt.application.name=Rvoice",
            "-Dapple.awt.enableTemplateImages=true",
            "-Xdock:name=Rvoice",
            "-Xdock:icon=${project.file("icons/Rvoice.png").absolutePath}",
        )
    }
}

val packagedNativeResources = layout.buildDirectory.dir("app-resources/macos")

tasks.register<Copy>("preparePackagedNativeTools") {
    dependsOn("compileDictationHelper", "compileMicrophoneRecorder", "compileSpeechSpeaker")
    from(rootProject.file("native/macos/ReaptiveVoiceLauncher"))
    from(rootProject.file("native/macos/ReaptiveVoiceMicrophone"))
    from(rootProject.file("native/macos/RvoiceSpeaker"))
    into(packagedNativeResources)
    filePermissions {
        unix("rwxr-xr-x")
    }
}

tasks.register<Copy>("preparePackagedHelperApp") {
    dependsOn("compileDictationHelper")
    from(rootProject.file("native/macos/Rvoice.app"))
    into(packagedNativeResources.map { it.dir("RvoiceHelper.app") })
    filePermissions {
        unix("rwxr-xr-x")
    }
    dirPermissions {
        unix("rwxr-xr-x")
    }
}

compose.desktop {
    application {
        mainClass = "com.reaptivevoice.app.MainKt"
        jvmArgs += listOf(
            "-Dapple.awt.application.name=Rvoice",
            "-Dapple.awt.enableTemplateImages=true",
            "-Xdock:name=Rvoice",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Rvoice"
            packageVersion = "1.0.0"
            description = "Dictation for the Mac"
            appResourcesRootDir.set(layout.buildDirectory.dir("app-resources"))
            modules(
                "java.base",
                "java.desktop",
                "java.datatransfer",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.localedata",
                "jdk.unsupported",
            )
            macOS {
                bundleID = "com.reaptivevoice.app"
                iconFile.set(project.file("icons/Rvoice.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Rvoice listens when you use the dictation shortcut, then types what you said.</string>
                        <key>CFBundleDisplayName</key>
                        <string>Rvoice</string>
                        <key>CFBundleName</key>
                        <string>Rvoice</string>
                    """.trimIndent()
                }
            }
        }
    }
}

tasks.matching { task ->
    task.name == "prepareAppResources" || task.name == "packageDmg" || task.name == "createDistributable" || task.name == "createReleaseDistributable"
}.configureEach {
    dependsOn("preparePackagedNativeTools", "preparePackagedHelperApp")
}

tasks.register<Exec>("chmodPackagedNativeTools") {
    dependsOn("preparePackagedNativeTools", "preparePackagedHelperApp")
    executable = "chmod"
    argumentProviders.add(
        object : CommandLineArgumentProvider {
            @get:Input
            val toolsDirectory = packagedNativeResources.map { directory ->
                directory.asFile.absolutePath
            }

            override fun asArguments(): Iterable<String> {
                return listOf("-R", "a+rx", toolsDirectory.get())
            }
        },
    )
}

tasks.matching { task ->
    task.name == "prepareAppResources"
}.configureEach {
    dependsOn("chmodPackagedNativeTools")
}

val packagedRvoiceApp = layout.buildDirectory.dir("compose/binaries/main/app/Rvoice.app")

tasks.register<Exec>("signRvoiceWithMicrophoneEntitlement") {
    dependsOn("createDistributable")
    val entitlementsFile = project.file("entitlements.plist")
    val appPath = packagedRvoiceApp.map { it.asFile.absolutePath }
    inputs.file(entitlementsFile)
    argumentProviders.add(CommandLineArgumentProvider { listOf(appPath.get()) })
    doFirst {
        commandLine(
            "codesign",
            "--force",
            "--sign",
            "-",
            "--entitlements",
            entitlementsFile.absolutePath,
            "--identifier",
            "com.reaptivevoice.app",
            appPath.get(),
        )
    }
}

tasks.matching { task ->
    task.name == "packageDmg"
}.configureEach {
    dependsOn("signRvoiceWithMicrophoneEntitlement")
}