/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package android_lint_reporter

import android_lint_reporter.github.GithubService
import android_lint_reporter.parser.Parser
import android_lint_reporter.parser.Renderer
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

open class AndroidLintReporterPluginExtension(
        var lintFilePath: String = "",
        var githubUsername: String = "",
        var githubRepositoryName: String = "")

class AndroidLintReporterPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("android_lint_reporter", AndroidLintReporterPluginExtension::class.java)

        project.tasks.register("parseAndSendLintResult") { task ->
            task.doLast {
                println("received extension: ${extension.githubUsername}/${extension.githubRepositoryName}")
                val projectProperties = project.properties
                val githubPullRequestId = projectProperties.get("githubPullRequestId") as String
                val githubToken = projectProperties.get("githubToken") as String
                // for debugging path
//                val fileTreeWalk = File("./").walkTopDown()
//                fileTreeWalk.forEach {
//                    if (it.name.contains("lint-results.xml")) {
//                        println("path: ${it.absolutePath}")
//                    }
//                }

                val issues = Parser.parse(File(extension.lintFilePath))
                val bodyString = Renderer.render(issues)

                val service = GithubService.create(
                        githubToken = githubToken,
                        username = extension.githubUsername,
                        repoName = extension.githubRepositoryName,
                        pullRequestId = githubPullRequestId
                )

                val response = service.postComment(bodyString).execute()
                if (response.isSuccessful) {
                    println("Lint result is posted to https://github.com/${extension.githubUsername}/${extension.githubRepositoryName}/${githubPullRequestId}!")
                } else {
                    println("An error has occurred... ")
                    println("code: ${response.code()}, message: ${response.message()}, body: ${response.errorBody()}")
                }
            }
        }
    }
}
