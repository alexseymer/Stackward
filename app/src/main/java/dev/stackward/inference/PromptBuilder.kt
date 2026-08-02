package dev.stackward.inference

object PromptBuilder {

    fun buildLogSummaryPrompt(
        logs: String,
        userQuestion: String? = null,
    ): String {
        val question = userQuestion?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "Summarize anomalies, errors, and likely root causes."

        return """
            You are Stackward, an on-device infrastructure monitoring assistant.
            Analyze the server logs below and answer in plain language.
            Do not invent services or hosts that are not present in the logs.
            If no issues are visible, say so clearly.

            User question: $question

            After your summary, if and only if a concrete follow-up action is warranted,
            append a JSON array inside a ```json fenced block using this schema:
            [
              {
                "tier": "ROUTINE",
                "action": "read_journal",
                "command": "exact shell command or API action",
                "reason": "why this helps",
                "target": "optional host/service/container",
                "backend": "SSH"
              }
            ]

            Valid tier values: ROUTINE, ONE_TIMER, BOUNDARY_CHANGE.
            Never propose BOUNDARY_CHANGE unless the user explicitly asked to change permissions.

            Logs:
            $logs
        """.trimIndent()
    }
}
