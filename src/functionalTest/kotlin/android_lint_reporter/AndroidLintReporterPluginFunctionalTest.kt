/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package android_lint_reporter

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidLintReporterPluginFunctionalTest {
    @Test
    fun `can run task`() {
        // Setup the test build
        val projectDir = File("build/functionalTest")
        projectDir.mkdirs()
        projectDir.resolve("settings.gradle").writeText("")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id('com.worker8.android_lint_reporter')
            }
            android_lint_reporter {
                lintFilePath = "./src/main/resources/lint-results.xml"
                githubUsername = "worker8"
                githubRepositoryName = "SimpleCurrency"
            }
        """)

        // Run the build
        val runner = GradleRunner.create()
        runner.forwardOutput()
        runner.withPluginClasspath()
        runner.withArguments(listOf("parseAndSendLintResult", "-PgithubToken=","-PgithubPullRequestId=4"))
        runner.withProjectDir(projectDir)
        val result = runner.build();

        // Verify the result
        assertTrue(result.output.contains("yes sir"))
    }
}
