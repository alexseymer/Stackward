package dev.stackward.check

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** check.sh returned output that isn't the JSON shape PRD.md §5.0 defines. */
class CheckScriptParseException(message: String) : Exception(message)

object CheckScriptParser {

    /** Parses the raw stdout of `~/.stackward/check.sh` into a [CheckResult]. */
    fun parse(raw: String): CheckResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw CheckScriptParseException("check.sh produced no output")
        }
        val json = try {
            JSONObject(trimmed)
        } catch (error: JSONException) {
            throw CheckScriptParseException("check.sh did not return valid JSON: ${error.message}")
        }
        return try {
            CheckResult(
                timestamp = json.getString("timestamp"),
                hostname = json.getString("hostname"),
                issues = json.getJSONArray("issues").toIssues(),
                coverage = json.optJSONObject("coverage")?.toCoverage() ?: CheckCoverage(),
                scriptVersion = json.optInt("version", 0),
            )
        } catch (error: JSONException) {
            throw CheckScriptParseException("check.sh JSON missing expected field: ${error.message}")
        }
    }

    /** Inverse of [parse] — used by [CheckResultStore] to round-trip through local storage. */
    fun toJsonString(result: CheckResult): String = JSONObject().apply {
        put("timestamp", result.timestamp)
        put("hostname", result.hostname)
        put("version", result.scriptVersion)
        put(
            "issues",
            JSONArray().apply {
                result.issues.forEach { issue ->
                    put(
                        JSONObject().apply {
                            put("type", issue.type)
                            put("severity", issue.severity)
                            put("message", issue.message)
                            issue.advice?.let { put("advice", it) }
                        },
                    )
                }
            },
        )
        put(
            "coverage",
            JSONObject().apply {
                put("journal", result.coverage.journal)
                put("docker", result.coverage.docker)
                put("systemd", result.coverage.systemd)
            },
        )
    }.toString()

    private fun JSONArray.toIssues(): List<CheckIssue> = buildList {
        for (index in 0 until length()) {
            val obj = getJSONObject(index)
            add(
                CheckIssue(
                    type = obj.getString("type"),
                    severity = obj.getString("severity"),
                    message = obj.getString("message"),
                    advice = obj.optString("advice").takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    private fun JSONObject.toCoverage() = CheckCoverage(
        journal = optString("journal", CheckCoverage.UNKNOWN),
        docker = optString("docker", CheckCoverage.UNKNOWN),
        systemd = optString("systemd", CheckCoverage.UNKNOWN),
    )
}
