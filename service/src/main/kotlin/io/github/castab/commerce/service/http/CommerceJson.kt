package io.github.castab.commerce.service.http

import kotlinx.serialization.KSerializer
import org.http4k.format.ConfigurableKotlinxSerialization
import org.http4k.lens.BiDiBodyLens

/**
 * The JSON format of every commerce HTTP body, through http4k's kotlinx.serialization
 * integration.
 *
 * - Unknown request fields are ignored, so clients can send additive fields.
 * - Properties with default values are always written.
 * - `null` optional properties are omitted from responses.
 *
 * Transport DTOs are `@Serializable` classes owned by the service layer. Domain types are
 * never serialized directly; routes translate between DTOs and domain values explicitly.
 */
object CommerceJson : ConfigurableKotlinxSerialization({
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
})

/**
 * A JSON body lens for [serializer]. Extracting a missing, unparsable, or incomplete body
 * fails with an http4k `LensFailure`, which the error filter reports as a malformed
 * request.
 */
fun <T : Any> jsonBody(serializer: KSerializer<T>): BiDiBodyLens<T> = CommerceJson.autoBody(serializer).toLens()
