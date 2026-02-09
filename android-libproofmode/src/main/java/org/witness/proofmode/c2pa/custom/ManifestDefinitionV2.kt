package org.witness.proofmode.c2pa.custom

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.contentauth.c2pa.DigitalSourceType
import org.contentauth.c2pa.manifest.ActionAssertion
import org.contentauth.c2pa.manifest.AssertionDefinition
import org.contentauth.c2pa.manifest.ClaimGeneratorInfo
import org.contentauth.c2pa.manifest.Ingredient
import org.contentauth.c2pa.manifest.ResourceRef

/**
 * Defines a C2PA manifest for content authenticity.
 *
 * ManifestDefinition is the root type for building C2PA manifests. It contains all the
 * information needed to create a signed manifest, including claims, assertions, and ingredients.
 *
 * ## Usage
 *
 * ```kotlin
 * val manifest = ManifestDefinition(
 *     title = "My Photo",
 *     claimGeneratorInfo = listOf(ClaimGeneratorInfo.fromContext(context)),
 *     assertions = listOf(
 *         AssertionDefinition.actions(listOf(
 *             ActionAssertion.created(DigitalSourceType.DIGITAL_CAPTURE)
 *         ))
 *     )
 * )
 *
 * // Convert to JSON for use with Builder
 * val json = manifest.toJson()
 * val builder = Builder.fromJson(json)
 * ```
 *
 * @property title The title of the asset.
 * @property claimGeneratorInfo Information about the software creating the claim.
 * @property assertions The list of assertions in this manifest.
 * @property ingredients The list of ingredients (parent assets) used in this manifest.
 * @property thumbnail Reference to a thumbnail image for this asset.
 * @property format The MIME type of the asset (e.g., "image/jpeg").
 * @property vendor An optional vendor identifier.
 * @property label An optional unique label for this manifest.
 * @property instanceId An optional instance identifier.
 * @property redactions A list of assertion URIs to redact from ingredients.
 * @see org.contentauth.c2pa.manifest.AssertionDefinition
 * @see org.contentauth.c2pa.manifest.Ingredient
 * @see org.contentauth.c2pa.manifest.ClaimGeneratorInfo
 */
@Serializable
data class ManifestDefinitionV2 (
    val title: String,
    @SerialName("claim_generator_info")
    val claimGeneratorInfo: List<ClaimGeneratorInfo>,
    @SerialName("created_assertions")
    val createdAssertions: List<AssertionDefinition> = emptyList(),
    @SerialName("gathered_assertions")
    val gatheredAssertions: List<AssertionDefinition> = emptyList(),
    val ingredients: List<Ingredient> = emptyList(),
    val thumbnail: ResourceRef? = null,
    val format: String? = null,
    val vendor: String? = null,
    val label: String? = null,
    @SerialName("instance_id")
    val instanceId: String? = null,
    val redactions: List<String>? = null,
) {
    /**
     * Converts this manifest definition to a JSON string.
     *
     * The resulting JSON can be used with [org.contentauth.c2pa.Builder.fromJson].
     *
     * @return The manifest as a JSON string.
     */
    fun toJson(): String = json.encodeToString(this)

    /**
     * Converts this manifest definition to a pretty-printed JSON string.
     *
     * @return The manifest as a formatted JSON string.
     */
    fun toPrettyJson(): String = prettyJson.encodeToString(this)

    override fun toString(): String = toJson()

    companion object {
        private val json = Json {
            encodeDefaults = false
            ignoreUnknownKeys = true
        }

        private val prettyJson = Json {
            encodeDefaults = false
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        /**
         * Parses a ManifestDefinition from a JSON string.
         *
         * @param jsonString The JSON string to parse.
         * @return The parsed ManifestDefinition.
         */
        fun fromJson(jsonString: String): ManifestDefinitionV2 =
            json.decodeFromString(jsonString)

        /**
         * Creates a minimal manifest definition for a newly created asset.
         *
         * @param title The title of the asset.
         * @param claimGeneratorInfo The claim generator info.
         * @param digitalSourceType The digital source type for the created action.
         */
        fun created(
            title: String,
            claimGeneratorInfo: ClaimGeneratorInfo,
            digitalSourceType: DigitalSourceType,
        ) = ManifestDefinitionV2 (
            title = title,
            claimGeneratorInfo = listOf(claimGeneratorInfo),
            createdAssertions = listOf(
                AssertionDefinition.Companion.action(
                    ActionAssertion.Companion.created(digitalSourceType),
                ),
            ),
        )
    }
}