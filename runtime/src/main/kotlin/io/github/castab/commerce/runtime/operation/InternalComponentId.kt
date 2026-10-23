package io.github.castab.commerce.runtime.operation

/**
 * Stable, application-chosen component name for operation attribution (for example
 * `example.inquiry-intake`). Not a [io.github.castab.commerce.staff.PrincipalId].
 *
 * One or more dot-separated segments of lowercase ASCII letters and digits, optionally
 * joined by single hyphens or underscores; at most 128 characters. Values are never
 * trimmed or normalized. Applications keep names stable across restarts and releases
 * and own any historical mapping when a component is renamed.
 */
data class InternalComponentId(
    val value: String,
) {
    init {
        require(value.length in 1..128 && COMPONENT_NAME.matches(value)) {
            "Internal component identifier must be 1-128 characters of lowercase dot-separated segments"
        }
    }
}

private val COMPONENT_NAME = Regex("[a-z0-9]+(?:[-_][a-z0-9]+)*(?:\\.[a-z0-9]+(?:[-_][a-z0-9]+)*)*")
