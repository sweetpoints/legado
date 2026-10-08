package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import java.security.MessageDigest

/** Aliases are supplied only after the repository validates the registered task context. */
internal class NativeOrgSourceOwners {
    private val lock = Any()
    private val aliasesByOwner = mutableMapOf<String, MutableSet<String>>()
    private val ownersByAlias = mutableMapOf<String, MutableSet<String>>()
    private val runtimeAliasesByOwner = mutableMapOf<String, MutableSet<String>>()

    fun bind(owner: String, vararg aliases: String) =
        synchronized(lock) {
            require(owner.isNotBlank())
            val names = aliasesByOwner.getOrPut(owner) { mutableSetOf() }
            runtimeAliasesByOwner
                .getOrPut(owner) { mutableSetOf() }
                .addAll(aliases.filter { it.isNotBlank() })
            (aliases.asList() + owner)
                .filter { it.isNotBlank() }
                .forEach { alias ->
                    names.add(alias)
                    ownersByAlias.getOrPut(alias) { mutableSetOf() }.add(owner)
                }
        }

    fun runtimeAliases(alias: String): Set<String> =
        synchronized(lock) {
            ownersByAlias[alias].orEmpty().flatMap { runtimeAliasesByOwner[it].orEmpty() }.toSet()
        }

    /** Remove every alias of the selected owners, preserving unrelated owners. */
    fun take(alias: String): Set<String> =
        synchronized(lock) {
            val owners = ownersByAlias[alias]?.toSet().orEmpty()
            owners.forEach { owner ->
                runtimeAliasesByOwner.remove(owner)
                aliasesByOwner.remove(owner)?.forEach { name ->
                    ownersByAlias[name]?.let { remaining ->
                        remaining.remove(owner)
                        if (remaining.isEmpty()) ownersByAlias.remove(name)
                    }
                }
            }
            owners
        }

    fun clear() =
        synchronized(lock) {
            aliasesByOwner.clear()
            ownersByAlias.clear()
            runtimeAliasesByOwner.clear()
        }

    companion object {
        /** Stable source configuration, never an entry script or a resolved dynamic header. */
        fun configurationFingerprint(source: BaseSource): String {
            val original = source.getSource() ?: source
            val recipe =
                linkedMapOf<String, Any?>(
                    "type" to original.javaClass.name,
                    "jsLib" to original.jsLib,
                    "header" to original.header,
                    "loginUrl" to original.loginUrl,
                    "loginUi" to original.loginUi,
                )
            if (original is BookSource) {
                recipe.putAll(
                    mapOf(
                        "mainJs" to original.mainJs,
                        "definition" to
                            original.bookSourceComment
                                .orEmpty()
                                .lineSequence()
                                .map { it.trim() }
                                .firstOrNull { it.startsWith("@source:v1 ") },
                        "searchUrl" to original.searchUrl,
                        "exploreUrl" to original.exploreUrl,
                        "ruleSearch" to original.ruleSearch,
                        "ruleExplore" to original.ruleExplore,
                        "ruleBookInfo" to original.ruleBookInfo,
                        "ruleToc" to original.ruleToc,
                        "ruleContent" to original.ruleContent,
                        "ruleReview" to original.ruleReview,
                        "loginCheckJs" to original.loginCheckJs,
                        "coverDecodeJs" to original.coverDecodeJs,
                    )
                )
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(GSON.toJson(recipe).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
