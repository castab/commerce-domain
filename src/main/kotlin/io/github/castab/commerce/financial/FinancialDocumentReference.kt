package io.github.castab.commerce.financial

import java.util.UUID

/**
 * Identifies exactly one immutable [FinancialDocument] snapshot: the lineage [id] and the
 * [version] within it.
 *
 * A reference never contains the snapshot it refers to. Snapshots point to their history
 * through references (see [FinancialDocument.previousReference]) rather than by embedding
 * earlier documents, so loading one snapshot never loads a chain of predecessors. A
 * reference maps naturally onto a persisted `(document_id, version)` key.
 */
public data class FinancialDocumentReference(
    public val id: UUID,
    public val version: Version,
)
