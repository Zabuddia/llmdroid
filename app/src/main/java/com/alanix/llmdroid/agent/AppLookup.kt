package com.alanix.llmdroid.agent

import android.content.Intent
import android.content.pm.PackageManager

object AppLookup {

    data class AppResult(val label: String, val packageName: String)

    @Suppress("DEPRECATION")
    fun findBest(pm: PackageManager, query: String): AppResult? {
        val queryNorm = normalize(query)
        if (queryNorm.isEmpty()) return null

        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == "com.alanix.llmdroid") return@mapNotNull null
            AppResult(ri.loadLabel(pm).toString(), pkg)
        }

        return apps
            .map { it to score(normalize(it.label), queryNorm) }
            .filter { (_, s) -> s > 0 }
            .maxByOrNull { (_, s) -> s }
            ?.first
    }

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun score(appNorm: String, queryNorm: String): Int {
        if (appNorm.isEmpty()) return 0
        if (appNorm.contains(queryNorm)) return 10_000
        var qi = 0
        var consecutive = 0
        var maxConsecutive = 0
        var total = 0
        for (c in appNorm) {
            if (qi < queryNorm.length && c == queryNorm[qi]) {
                qi++; consecutive++; total++
                if (consecutive > maxConsecutive) maxConsecutive = consecutive
            } else {
                consecutive = 0
            }
        }
        if (total < (queryNorm.length + 1) / 2) return 0
        return maxConsecutive * 100 + total
    }
}
