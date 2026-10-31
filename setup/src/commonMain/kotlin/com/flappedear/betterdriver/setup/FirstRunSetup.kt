package com.flappedear.betterdriver.setup

/**
 * The first-run checklist (UI-13 option B, FBBD-83), shared by Android and iOS (FBBD-371): one page,
 * steps in any order, only permissions needed to finish. Each app asks for its own permissions and
 * draws the rows; the steps, their order, texts and what "done" means come from here, so both apps
 * show the same page.
 */
enum class FirstRunStepKind { PERMISSIONS, GNSS, CAMERAS, CAR }

/** One row of the checklist; [action] is the button's text, none once nothing is left to do there. */
data class FirstRunStep(
    val kind: FirstRunStepKind,
    val title: String,
    val detail: String,
    val done: Boolean,
    val action: String?,
    val notes: List<String> = emptyList(),
)

/** What the phone has granted: [requiredGranted] = location and nearby devices, [allGranted] = optional ones too. */
data class PermissionsState(
    val requiredGranted: Boolean,
    val allGranted: Boolean,
    /** The system shows no dialog any more: only its settings can grant. */
    val blocked: Boolean = false,
    /** The groups still off, once the dialog has answered (FBBD-35). */
    val denied: List<PermissionLine> = emptyList(),
)

/**
 * What stops working when a permission is off (FBBD-35), one line per group as Android's permission
 * screen groups them, in this order.
 */
enum class PermissionLine(val title: String, val effect: String) {
    LOCATION("Location", "no GNSS from the phone; Bluetooth GPS receivers also need it"),
    NEARBY_DEVICES("Nearby devices", "no RaceBox, GoPro or Bluetooth receivers"),
    CAMERA("Camera", "no phone video"),
    MICROPHONE("Microphone", "video without sound"),
    NOTIFICATIONS("Notifications", "no recording notification"),
    ;

    /** "Camera off: no phone video". */
    val deniedLine: String get() = "$title off: $effect"
}

object FirstRunSetup {
    const val TITLE = "Set up"
    const val DONE = "Done"
    const val PERMISSIONS_FIRST = "Allow location and nearby devices first."
    const val PERMISSIONS_DETAIL = "Location and nearby devices · camera, mic and notifications optional"

    /** The steps in Android's order: permissions, GNSS receiver, cameras, car. */
    fun steps(setup: Setup, permissions: PermissionsState): List<FirstRunStep> {
        val active = setup.active()
        return listOf(
            FirstRunStep(
                FirstRunStepKind.PERMISSIONS,
                "Permissions",
                PERMISSIONS_DETAIL,
                done = permissions.requiredGranted,
                action = when {
                    permissions.blocked -> "Settings"
                    permissions.allGranted -> null
                    else -> "Allow"
                },
                notes = permissions.denied.map { it.deniedLine },
            ),
            FirstRunStep(
                FirstRunStepKind.GNSS,
                "GNSS receiver",
                active.gnss?.name ?: "Needed for lap timing",
                done = active.gnss != null,
                action = if (active.gnss == null) "Add" else null,
            ),
            FirstRunStep(
                FirstRunStepKind.CAMERAS,
                "Cameras",
                active.cameras.joinToString(", ") { it.name }.ifEmpty { "None (optional)" },
                done = active.cameras.isNotEmpty(),
                action = "Set up",
            ),
            FirstRunStep(
                FirstRunStepKind.CAR,
                "Car",
                active.car?.name ?: "Optional",
                done = active.car != null,
                action = if (setup.cars.isEmpty()) "Add" else "Choose",
            ),
        )
    }

    /** "2 of 4 done. …", above the rows. */
    fun summary(done: Int, total: Int): String = "$done of $total done. You can start without the optional steps."

    /** Done is enabled once the required permissions are granted; the rest is optional. */
    fun canFinish(steps: List<FirstRunStep>): Boolean =
        steps.firstOrNull { it.kind == FirstRunStepKind.PERMISSIONS }?.done == true

    /**
     * Whether the app opens on the checklist: not on a tablet, which only reviews (Arek 2026-10-30),
     * and else until Done was pressed, or again when a required permission was taken back.
     */
    fun opensOnSetup(tablet: Boolean, setupDone: Boolean, requiredGranted: Boolean): Boolean =
        !tablet && !(setupDone && requiredGranted)
}
