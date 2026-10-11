package com.flappedear.betterdriver.updater

/** A release version X.Y.Z, as in gradle.properties and the release tags (vX.Y.Z). */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {

    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val Pattern = Regex("""v?(\d{1,6})\.(\d{1,6})\.(\d{1,6})""")

        /** "0.1.3" or "v0.1.3"; null for anything else ("0.1", "0.1.3-rc1", "latest"). */
        fun parse(text: String): Version? = Pattern.matchEntire(text.trim())?.destructured?.let { (a, b, c) ->
            Version(a.toInt(), b.toInt(), c.toInt())
        }
    }
}
