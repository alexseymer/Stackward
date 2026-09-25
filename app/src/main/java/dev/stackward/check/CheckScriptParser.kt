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
                suggestions = json.getJSONArray("suggestions").toSuggestions(),
            )
        } catch (error: JSONException) {
            throw CheckScriptParseException("check.sh JSON missing expected field: ${error.message}")
        }
    }

    /** Inverse of [parse] — used by [CheckResultStore] to round-trip through local storage. */
    fun toJsonString(result: CheckResult): String = JSONObject().apply {
        put("timestamp", result.timestamp)
        put("hostname", result.hostname)
        put(
            "issues",
            JSONArray().apply {
                result.issues.forEach { issue ->
                    put(
                        JSONObject().apply {
                            put("type", issue.type)
                            put("severity", issue.severity)
                            put("message", issue.message)
                        },
                    )
                }
            },
        )
        put(
            "suggestions",
            JSONArray().apply {
                result.suggestions.forEach { suggestion ->
                    put(
                        JSONObject().apply {
                            put("id", suggestion.id)
                            put("risk", suggestion.risk)
                            put("action", suggestion.action)
                            put("reason", suggestion.reason)
                        },
                    )
                }
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
                ),
            )
        }
    }

    private fun JSONArray.toSuggestions(): List<CheckSuggestion> = buildList {
        for (index in 0 until length()) {
            val obj = getJSONObject(index)
            add(
                CheckSuggestion(
                    id = obj.getString("id"),
                    risk = obj.getString("risk"),
                    action = obj.getString("action"),
                    reason = obj.optString("reason"),
                ),
            )
        }
    }
}
