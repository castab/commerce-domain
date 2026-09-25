package io.github.castab.commerce.financial

import java.util.UUID

/**
 * An explicit, immutable commercial modification of a [FinancialDocument].
 *
 * Applying a change order with [FinancialDocument.changeOrder] produces a new snapshot at
 * the next version, in the same lifecycle stage. The source snapshot is never modified.
 *
 * The [changes] are applied in order, and atomically: if any change cannot be applied, or
 * the result would be an invalid document, no successor is produced.
 *
 * Changes work on whole line items. There is no field-level patching: to change a line,
 * replace it with a revised copy that keeps its id.
 *
 * The list of changes is copied on construction, so later changes to the caller's list do
 * not affect the change order. A change order must contain at least one change.
 */
class ChangeOrder(changes: List<Change>) {

    /** The changes to apply, in order. Never empty. */
    val changes: List<Change> = changes.toImmutableList()

    init {
        require(this.changes.isNotEmpty()) { "A change order must contain at least one change" }
    }

    override fun equals(other: Any?): Boolean = other is ChangeOrder && other.changes == changes

    override fun hashCode(): Int = changes.hashCode()

    override fun toString(): String = "ChangeOrder(changes=$changes)"

    /** One step of a [ChangeOrder]. */
    sealed interface Change {

        /**
         * Appends [lineItem] to the document.
         *
         * Fails if the document already contains a line item with the same id.
         */
        data class AddLineItem(
            val lineItem: LineItem,
        ) : Change

        /**
         * Replaces the existing line item [lineItemId] with [replacement], keeping its position.
         *
         * The replacement must keep the same id, so a line item's identity never changes.
         * Fails if the document has no line item with [lineItemId].
         *
         * @throws IllegalArgumentException on construction if `replacement.id` differs from
         * [lineItemId].
         */
        data class ReplaceLineItem(
            val lineItemId: UUID,
            val replacement: LineItem,
        ) : Change {
            init {
                require(replacement.id == lineItemId) {
                    "Replacement line item ${replacement.id} must keep the id of the line item it replaces, $lineItemId"
                }
            }
        }

        /**
         * Removes the existing line item [lineItemId].
         *
         * Fails if the document has no line item with that id.
         */
        data class RemoveLineItem(
            val lineItemId: UUID,
        ) : Change
    }
}
