package com.flappedear.betterdriver.recorder.log

/**
 * What Settings › Equipment showed about each listed item's reach (UI-138 B, FBBD-280), as the
 * continuous log keeps it, so the drive's checks can tell whether "In range" and "Not seen" were
 * right. The lines, tag [TAG], written by the UI process outside any recording:
 * - "look on items=<count>" when the page starts looking for Bluetooth devices, "look off" when it
 *   stops (the page closed, or a recording started).
 * - "item=<id> model=<model> address=<address or -> reach=<state or none>" each time an item's state
 *   changes, its first state included; "none" when the page shows no line for it.
 */
object ReachLog {
    const val TAG = "Reach"
    const val NONE = "none"
    private const val NoAddress = "-"

    fun look(on: Boolean, items: Int) = if (on) "look on items=$items" else "look off"

    fun item(id: String, model: String, address: String?, reach: String?) =
        "item=${word(id)} model=${word(model)} address=${address?.let(::word) ?: NoAddress} reach=${reach ?: NONE}"

    /**
     * The lines that tell how [before] became [after] (each an item's id to its model, address and
     * state): one per item whose state changed or that is new. An item that left the list is not
     * logged; it is no longer shown.
     */
    fun changes(before: Map<String, Shown>, after: Map<String, Shown>): List<String> =
        after.filter { (id, shown) -> before[id] != shown }
            .map { (id, s) -> item(id, s.model, s.address, s.reach) }

    /** What [message] of a [TAG] line says, or null when it is none of these. */
    fun parse(message: String): Entry? {
        val f = CheckLog.fields(message)
        return when {
            message.startsWith("look on") -> Entry.Look(true)
            message == "look off" -> Entry.Look(false)
            message.startsWith("item=") -> {
                val id = f["item"]
                val reach = f["reach"]
                if (id == null || reach == null) {
                    null
                } else {
                    Entry.Item(id, f["model"].orEmpty(), f["address"]?.takeIf { it != NoAddress }, reach)
                }
            }
            else -> null
        }
    }

    private fun word(text: String) = text.replace(' ', '_')

    /** How the page showed one item: its [model], [address] and [reach] state (null: no line). */
    data class Shown(val model: String, val address: String?, val reach: String?)

    sealed interface Entry {
        /** The page started ([on]) or stopped looking. */
        data class Look(val on: Boolean) : Entry

        /** Item [id] was shown as [reach] ([NONE]: no line). */
        data class Item(val id: String, val model: String, val address: String?, val reach: String) : Entry
    }
}
