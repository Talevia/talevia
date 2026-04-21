package io.talevia.core.domain.source.genre.ad

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import kotlinx.serialization.json.Json

/**
 * Typed builders and accessors for the Ad / Marketing genre.
 *
 * Mirrors the other genre ext files: writes go through [Source.addNode]
 * with a body pre-encoded via the canonical [Json] (`JsonConfig.default`).
 * Reads decode only if [SourceNode.kind] matches the expected constant;
 * otherwise the accessor returns `null`.
 *
 * `add*` helpers accept optional `parents`. Variants typically depend on
 * both the brand brief and the product spec; wiring those parents at
 * construction is what lets an edit to the brief flow through the DAG
 * (`Source.stale`) to every variant that has to be re-cut.
 */

private val json: Json = JsonConfig.default

// region — writers

fun Source.addAdBrandBrief(
    id: SourceNodeId,
    body: AdBrandBriefBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = AdNodeKinds.BRAND_BRIEF,
        body = json.encodeToJsonElement(AdBrandBriefBody.serializer(), body),
        parents = parents,
    ),
)

fun Source.addAdProductSpec(
    id: SourceNodeId,
    body: AdProductSpecBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = AdNodeKinds.PRODUCT_SPEC,
        body = json.encodeToJsonElement(AdProductSpecBody.serializer(), body),
        parents = parents,
    ),
)

fun Source.addAdVariantRequest(
    id: SourceNodeId,
    body: AdVariantRequestBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = AdNodeKinds.VARIANT_REQUEST,
        body = json.encodeToJsonElement(AdVariantRequestBody.serializer(), body),
        parents = parents,
    ),
)

// endregion

// region — readers

/** @return the decoded body when [SourceNode.kind] matches, else `null`. */
fun SourceNode.asAdBrandBrief(): AdBrandBriefBody? =
    if (kind == AdNodeKinds.BRAND_BRIEF) {
        json.decodeFromJsonElement(AdBrandBriefBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asAdProductSpec(): AdProductSpecBody? =
    if (kind == AdNodeKinds.PRODUCT_SPEC) {
        json.decodeFromJsonElement(AdProductSpecBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asAdVariantRequest(): AdVariantRequestBody? =
    if (kind == AdNodeKinds.VARIANT_REQUEST) {
        json.decodeFromJsonElement(AdVariantRequestBody.serializer(), body)
    } else {
        null
    }

// endregion
