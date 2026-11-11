package io.github.igrvlhlb.secrt.backup.drive

import io.github.igrvlhlb.secrt.backup.BackupException

/**
 * Google Drive answered, but not as hoped: see [problem]. The message is English, for logs and
 * tests; the UI has its own text for each problem.
 */
class GoogleDriveException(
    val problem: Problem,
    /** What was being done, for [Problem.NotAllowed] and [Problem.Failed]. */
    val action: Action? = null,
    /** The file concerned, if [action] is about one. */
    val path: String? = null,
    /** The HTTP status, for [Problem.Failed]. */
    val status: Int? = null,
    /** Google's own explanation (English), for [Problem.NotAllowed]. */
    val detail: String? = null,
) : BackupException(englishMessage(problem, action, path, status, detail)) {

    enum class Problem { StorageFull, ApiDisabled, NotAllowed, Failed, UploadNotStarted }

    enum class Action(internal val english: String) {
        ListFiles("list the backup's files"),
        Download("download"),
        Upload("upload"),
        Delete("delete"),
    }

    private companion object {
        fun englishMessage(problem: Problem, action: Action?, path: String?, status: Int?, detail: String?): String {
            val what = if (action == Action.ListFiles) action.english else listOfNotNull(action?.english, path).joinToString(" ")
            return when (problem) {
                Problem.StorageFull -> "Your Google storage is full"
                Problem.ApiDisabled -> "The Google Drive API isn't enabled for this build of secrt"
                Problem.NotAllowed -> "Google Drive doesn't let secrt $what" + (detail?.let { ": $it" } ?: "")
                Problem.Failed -> "Google Drive couldn't $what (HTTP $status)"
                Problem.UploadNotStarted -> "Google Drive didn't start the upload"
            }
        }
    }
}
