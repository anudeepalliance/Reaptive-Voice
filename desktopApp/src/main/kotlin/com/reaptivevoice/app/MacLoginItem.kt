package com.reaptivevoice.app

import java.io.File

/** Passed to Rvoice by the login item, so a start at login stays in the menu bar without opening the window. */
const val startedAtLoginLaunchArgument = "--started-at-login"

/**
 * Turns on "open when you log in" by installing a per-user login item. It starts Rvoice in the background with only its menu bar icon, no window. If Rvoice is already open, the login item does nothing, so checking the box does not start a second copy.
 */
class MacLoginItem(
    private val projectRoot: File,
    private val agentFile: File = File(System.getProperty("user.home"), "Library/LaunchAgents/com.reaptivevoice.app.plist"),
    private val installedApp: File = File("/Applications/Rvoice.app"),
) {
    fun isEnabled(): Boolean {
        return agentFile.isFile
    }

    fun setEnabled(isEnabled: Boolean): String? {
        val userId = currentUserId()
        if (!isEnabled) {
            runLaunchctl("bootout", "gui/$userId/com.reaptivevoice.app")
            agentFile.delete()
            return null
        }
        agentFile.parentFile?.mkdirs()
        agentFile.writeText(loginAgentPlist())
        runLaunchctl("bootout", "gui/$userId/com.reaptivevoice.app")
        val output = runLaunchctl("bootstrap", "gui/$userId", agentFile.absolutePath)
        if (output.exitCode != 0 && !output.text.contains("already loaded", ignoreCase = true)) {
            return "Could not turn on open at login. ${output.text.trim()}"
        }
        return null
    }

    /**
     * Rewrites a login item file on disk that was written by an older Rvoice, so it starts the installed app in the background without a window. A file that already does that, or no file (open at login is off), is left as it is. macOS picks up the change at the next login.
     *
     * Why: older files either started the Gradle copy, which cannot see the installed app running and opened a second Rvoice at every login, or opened the full window at login.
     */
    fun rewriteOutdatedLoginItemFileOnDisk() {
        if (!agentFile.isFile) {
            return
        }
        if (!agentFile.readText().contains(xmlEscape(commandThatStartsRvoiceAtLogin()))) {
            agentFile.writeText(loginAgentPlist())
        }
    }

    /** Opens the installed app in the background (`-g`, so it does not take focus) when it exists; otherwise runs the Gradle copy unless one is already running. */
    private fun commandThatStartsRvoiceAtLogin(): String {
        if (installedApp.isDirectory) {
            return "open -g -a ${installedApp.absolutePath} --args $startedAtLoginLaunchArgument"
        }
        return "if pgrep -f 'com.reaptivevoice.app.MainKt' >/dev/null; then exit 0; fi; cd '${projectRoot.absolutePath}' && exec ./gradlew :desktopApp:run --console=plain --args=$startedAtLoginLaunchArgument"
    }

    private fun loginAgentPlist(): String {
        val home = System.getProperty("user.home")
        val javaHome = System.getProperty("java.home")
        val projectPath = projectRoot.absolutePath
        val logPath = "$home/.reaptive-voice/login-launch.log"
        val command = commandThatStartsRvoiceAtLogin()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
            	<key>Label</key>
            	<string>com.reaptivevoice.app</string>
            	<key>WorkingDirectory</key>
            	<string>${xmlEscape(projectPath)}</string>
            	<key>RunAtLoad</key>
            	<true/>
            	<key>ProgramArguments</key>
            	<array>
            		<string>/bin/zsh</string>
            		<string>-c</string>
            		<string>${xmlEscape(command)}</string>
            	</array>
            	<key>EnvironmentVariables</key>
            	<dict>
            		<key>JAVA_HOME</key>
            		<string>${xmlEscape(javaHome)}</string>
            	</dict>
            	<key>StandardOutPath</key>
            	<string>${xmlEscape(logPath)}</string>
            	<key>StandardErrorPath</key>
            	<string>${xmlEscape(logPath)}</string>
            </dict>
            </plist>
        """.trimIndent()
    }

    private fun currentUserId(): String {
        val process = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().decodeToString().trim()
        process.waitFor()
        return text.ifBlank { System.getProperty("user.name") }
    }

    private fun runLaunchctl(vararg arguments: String): LaunchctlResult {
        val process = ProcessBuilder(listOf("launchctl") + arguments).redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().decodeToString()
        val exitCode = process.waitFor()
        return LaunchctlResult(exitCode, text)
    }

    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}

private data class LaunchctlResult(
    val exitCode: Int,
    val text: String,
)
