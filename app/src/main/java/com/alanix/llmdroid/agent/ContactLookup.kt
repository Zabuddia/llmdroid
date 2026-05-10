package com.alanix.llmdroid.agent

import android.content.ContentResolver
import android.provider.ContactsContract
import android.util.Log

object ContactLookup {

    private const val TAG = "ContactLookup"

    data class Result(val name: String, val number: String)

    /**
     * Finds the contact whose name best matches [query] using a scoring function that rewards
     * consecutive character runs and total matching characters (adapted from Dicio's
     * contactStringDistance). Returns null if no contact clears the minimum threshold.
     */
    fun findBest(contentResolver: ContentResolver, query: String): Result? {
        val q = normalize(query)
        if (q.isEmpty()) return null

        data class Candidate(val name: String, val number: String, val score: Int)

        val candidates = mutableListOf<Candidate>()

        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} IS NOT NULL",
                null,
                null,
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol  = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seen = mutableSetOf<String>() // deduplicate by name+number
                while (cursor.moveToNext()) {
                    val name   = cursor.getString(nameCol) ?: continue
                    val number = cursor.getString(numCol)  ?: continue
                    val key = "$name|$number"
                    if (!seen.add(key)) continue

                    val score = score(q, normalize(name))
                    if (score > 0) candidates.add(Candidate(name, number, score))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact query failed", e)
            return null
        }

        return candidates.maxByOrNull { it.score }?.let { Result(it.name, it.number) }
    }

    /** Lower-cased, letters and digits only — matches Dicio's cleanStringForDistance. */
    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Score = maxConsecutiveMatchingChars + totalMatchingChars, using a greedy left-to-right
     * scan of the query characters through the name. Returns 0 if fewer than half the query
     * characters are matched (i.e. wildly wrong name).
     */
    private fun score(query: String, name: String): Int {
        // Exact substring = very high score
        if (name.contains(query)) return 10_000 + name.length

        var qIdx = 0
        var consecutive = 0
        var maxConsecutive = 0
        var totalMatched = 0

        for (ch in name) {
            if (qIdx < query.length && ch == query[qIdx]) {
                qIdx++
                consecutive++
                totalMatched++
                if (consecutive > maxConsecutive) maxConsecutive = consecutive
            } else {
                consecutive = 0
            }
        }

        // Require at least half the query chars to match
        if (totalMatched < (query.length + 1) / 2) return 0

        return maxConsecutive + totalMatched
    }
}
