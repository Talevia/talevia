package io.talevia.core.domain.source

import io.talevia.core.ProjectId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore

/**
 * Mutate only the [Source] portion of a project under the existing [ProjectStore] mutex.
 *
 * Kept in its own file (rather than inside `ProjectStore.kt`) to minimise merge overlap
 * with parallel lanes touching the store. It is a thin adapter over [ProjectStore.mutate]
 * — there is no second mutex; the `ProjectStore` guarantee is the only ordering guarantee.
 */
suspend fun ProjectStore.mutateSource(
    id: ProjectId,
    block: suspend (Source) -> Source,
): Project = mutate(id) { it.copy(source = block(it.source)) }
