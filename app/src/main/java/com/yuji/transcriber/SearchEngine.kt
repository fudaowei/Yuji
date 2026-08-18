package com.yuji.transcriber

data class SearchResult(
    val entry: TranscriptEntry,
    val snippet: String,
)

object SearchEngine {

    fun search(query: String): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        return TranscriptRepository.loadAll().mapNotNull { entry ->
            val idx = entry.body.indexOf(q, ignoreCase = true)
            if (idx == -1) return@mapNotNull null

            val start = maxOf(0, idx - 20)
            val end = minOf(entry.body.length, idx + q.length + 40)
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < entry.body.length) "…" else ""
            val snippet = prefix + entry.body.substring(start, end) + suffix

            SearchResult(entry, snippet)
        }
    }
}
