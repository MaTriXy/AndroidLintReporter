/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package android_lint_reporter

import android_lint_reporter.github.GithubService
import android_lint_reporter.model.Issue
import android_lint_reporter.parser.Parser
import android_lint_reporter.parser.Renderer
import android_lint_reporter.util.print
import android_lint_reporter.util.printLog
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.lang.NumberFormatException
import java.util.*

open class AndroidLintReporterPluginExtension(
        var lintFilePath: String = "",
        var detektFilePath: String = "",
        var githubOwner: String = "",
        var githubRepositoryName: String = "",
        var showLog: Boolean = false
)

class AndroidLintReporterPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("android_lint_reporter", AndroidLintReporterPluginExtension::class.java)
        project.tasks.register("report") { task ->
            task.doLast {
                val projectProperties = project.properties
                val githubPullRequestId = projectProperties["githubPullRequestId"] as String
                val githubToken = projectProperties["githubToken"] as String
                val isDebug = projectProperties["isDebug"] as? String
                val projectRootDir = if (isDebug?.toBoolean() == true) {
                    // replace this with your CI root environment for testing
                    "/home/runner/work/${extension.githubRepositoryName}/${extension.githubRepositoryName}/"
                } else {
                    project.rootProject.projectDir.path
                }
                // uncomment - for debugging path
                // val fileTreeWalk = File("./").walkTopDown()
                // fileTreeWalk.forEach {
                //     if (it.name.contains("lint-results.xml")) {
                //         printLog("path: ${it.absolutePath}")
                //     }
                // }
                val service = GithubService.create(
                        githubToken = githubToken,
                        username = extension.githubOwner,
                        repoName = extension.githubRepositoryName,
                        pullRequestId = githubPullRequestId
                )
                val botUsername = service.getUser().execute().body()?.login
                if (extension.lintFilePath.length > 1 && extension.lintFilePath[0] == '.') {
                    // example: this is to replace "./src/main/resources/lint-results.xml" into "<projectDir>/src/main/resources/lint-results.xml"
                    extension.lintFilePath = "${project.projectDir.absolutePath}${extension.lintFilePath.substring(1)}"
                }
                if (extension.detektFilePath.length > 1 && extension.detektFilePath[0] == '.') {
                    // example: this is to replace "./src/main/resources/detekt_report.xml" into "<projectDir>/src/main/resources/detekt_report.xml"
                    extension.detektFilePath = "${project.projectDir.absolutePath}${extension.detektFilePath.substring(1)}"
                }

                /* parse lint issues */
                val githubIssues = Parser.parse(File(extension.lintFilePath))
                val detektIssues = Parser.parseDetektXml(File(extension.detektFilePath))
                printLog("Number of Android Lint Issues: ${githubIssues.size}")
                printLog("Number of Detekt Issues: ${detektIssues.size}")
                val combinedLineHashMap = hashMapOf<String, MutableSet<Int>>()
                val combinedIssueHashMap = hashMapOf<String, Issue>()
                (detektIssues + githubIssues).forEach { issue ->
                    val filename = issue.file.replace(projectRootDir, "")
                    val set = combinedLineHashMap[filename] ?: mutableSetOf()
                    // TODO: handle the case where there's no line number: send a table accumulating all the errors/warnings as a separate comment
                    val line = issue.line ?: -1
                    try {
                        set.add(line)
                    } catch (e: NumberFormatException) {
                        // for image files, like asdf.png, it doesn't have lines, so it will cause NumberFormatException
                        // add -1 in that case
                        set.add(-1)
                    }
                    combinedIssueHashMap[lintIssueKey(filename, line)] = issue
                    combinedLineHashMap[filename] = set
                }
                try {
                    /* get Pull Request files */
                    val prFileResponse = service.getPullRequestFiles().execute()
                    val files = prFileResponse.body()!!
                    val fileHashMap = hashMapOf<String, TreeMap<Int, Int>>()
                    files.forEach { githubPullRequestFilesResponse ->
                        val patch = githubPullRequestFilesResponse.patch
                        val regex = """@@ -(\d+),(\d+) \+(\d+),(\d+) @@""".toRegex()
                        val matchGroups = regex.findAll(patch)
                        val treeMap = TreeMap<Int, Int>() // line change start, how many lines
                        matchGroups.forEach { value ->
                            treeMap[value.groupValues[3].toInt()] = value.groupValues[3].toInt() + value.groupValues[4].toInt() - 1
                        }
                        fileHashMap[githubPullRequestFilesResponse.filename] = treeMap
                    }
                    if (extension.showLog) {
                        printLog("----Files change in this Pull Request----")
                        fileHashMap.entries.forEach { (filename, treeMap) ->
                            if (treeMap.isNotEmpty()) {
                                val pairString = treeMap.map { (a, b) ->
                                    "($a, $b)"
                                }.reduce { acc, s -> "$acc, $s" }
                                printLog("$filename -> $pairString")
                            }
                        }
                    }

                    /* get all comments from a pull request */
                    // commentHashMap is used to check for duplicated comments
                    val commentHashMap = hashMapOf<String, MutableSet<Int>>() // commentHashMap[filename] -> line number
                    val commentResults = service.getPullRequestComments().execute()
                    commentResults.body()?.forEach { comment ->
                        comment.line?.let { commentLine ->
                            if (botUsername == comment.user.login) {
                                val set = commentHashMap[comment.path] ?: mutableSetOf()
                                set.add(commentLine)
                                commentHashMap[comment.path] = set
                            }
                        }
                    }
                    printLog("Number of comments found in PR: ${commentHashMap.size}")
                    if (extension.showLog) {
                        printLog("----List of Comments in this Pull Request----")
                        commentHashMap.forEach { (filename, lineSet) ->
                            printLog("$filename -> ${lineSet.print()}")
                        }
                        if (commentHashMap.isEmpty()) {
                            printLog("0 comments found in this PR...")
                        }
                    }
                    /* check if lint issues are introduced in the files in this Pull Request */
                    /* then check the comments to see if was previously posted, to prevent duplication */
                    if (extension.showLog) {
                        printLog("----List of Issues Locations----")
                    }
                    combinedLineHashMap.forEach { (filename, lineSet) ->

                        lineSet.forEach { lintLine ->
                            // if violated lint file is introduced in this PR, it will be found in fileHashMap
                            if (extension.showLog) {
                                val issue = combinedIssueHashMap[lintIssueKey(filename, lintLine)]
                                printLog("$filename:$lintLine (${issue?.reporter})")
                            }
                            if (fileHashMap.find(filename, lintLine) && commentHashMap[filename]?.contains(lintLine) != true) {
                                // post to github as a review comment
                                val issue = combinedIssueHashMap[lintIssueKey(filename, lintLine)]
                                if (extension.showLog) {
                                    printLog("new issue found: $issue")
                                }
                                if (issue?.message != null) {
                                    try {
                                        val commitResult = service.getPullRequestCommits().execute()
                                        commitResult.body()?.last()?.sha?.let { lastCommitId ->
                                            val postReviewCommitResult = service.postReviewComment(
                                                    bodyString = Renderer.render(issue),
                                                    lineNumber = issue.line ?: 0,
                                                    path = issue.file.replace(projectRootDir, ""),
                                                    commitId = lastCommitId
                                            ).execute()
                                            if (postReviewCommitResult.isSuccessful) {
                                                printLog("report successfully posted to Pull Request #$githubPullRequestId")
                                            } else {
                                                printLog("Result cannot be posted due to error: ${postReviewCommitResult.errorBody()?.string()}")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        printLog("posting comment failed :(\n error mesage: ${e.message}")
                                        e.printStackTrace()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    printLog("error msg: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    private fun lintIssueKey(filename: String, line: Int): String {
        return "$filename:${line}"
    }
}

fun HashMap<String, TreeMap<Int, Int>>.find(targetFilename: String, targetLine: Int): Boolean {
    val entry: MutableMap.MutableEntry<Int, Int>? = this[targetFilename]?.floorEntry(targetLine)
    if (entry != null && (entry.key <= targetLine && entry.value >= targetLine)) {
        // found!
        return true
    }
    return false
}
