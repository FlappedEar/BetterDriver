package com.flappedear.betterdriver.timing.track

/**
 * [this] track as it is stored over [previous], the copy saved now (null for a new track), on every
 * platform's track storage: the next revision, [nowUtcMillis] as its update time, and the stored
 * corner identity (FBBD-172) kept, so an editor or an import holding an older one, or none, never
 * renames the corners there. A new track keeps the identity it comes with.
 *
 * With [adoptIdentity], for a track received from the other device with the driver's corner
 * corrections (FBBD-179), its identity replaces the one there as the next revision after it
 * ([adoptedOver]).
 */
fun Track.storedOver(previous: Track?, nowUtcMillis: Long, adoptIdentity: Boolean = false): Track {
    val received = identity?.takeIf { adoptIdentity }
    return copy(
        revision = maxOf(revision, (previous?.revision ?: 0) + 1),
        updatedUtcMillis = nowUtcMillis,
        identity = received?.adoptedOver(previous?.identity) ?: previous?.identity ?: identity,
    )
}
