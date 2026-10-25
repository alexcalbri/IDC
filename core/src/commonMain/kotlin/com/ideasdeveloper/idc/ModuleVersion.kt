package com.ideasdeveloper.idc

/**
 * Compares module versions such as 0.2.0, 0.10.1 or 1.0.0-beta: numeric parts compare as numbers
 * (0.10.0 is newer than 0.9.0) and a pre-release is older than its release.
 */
object ModuleVersion {
    fun compare(first: String, second: String): Int {
        val (firstNumbers, firstLabel) = parse(first)
        val (secondNumbers, secondLabel) = parse(second)
        for (index in 0 until maxOf(firstNumbers.size, secondNumbers.size)) {
            val difference = firstNumbers.getOrElse(index) { 0 }.compareTo(secondNumbers.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return when {
            firstLabel == secondLabel -> 0
            firstLabel == null -> 1
            secondLabel == null -> -1
            else -> firstLabel.compareTo(secondLabel)
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private fun parse(version: String): Pair<List<Long>, String?> {
        val value = version.trim().removePrefix("v")
        val numbers = value.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0 }
        val label = value.substringAfter('-', "").ifBlank { null }
        return numbers to label
    }
}
