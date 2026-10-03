package io.legado.app.data.preferences

import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.postEvent

enum class PaddingRegion {
    HEADER,
    BODY,
    FOOTER,
}

enum class PaddingSide {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT;

    val maximum
        get() = if (this == TOP || this == BOTTOM) 400 else 100
}

data class RegionPadding(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
    val showLine: Boolean = false,
) {
    operator fun get(side: PaddingSide) =
        when (side) {
            PaddingSide.TOP -> top
            PaddingSide.BOTTOM -> bottom
            PaddingSide.LEFT -> left
            PaddingSide.RIGHT -> right
        }

    fun with(side: PaddingSide, value: Int) =
        when (side) {
            PaddingSide.TOP -> copy(top = value)
            PaddingSide.BOTTOM -> copy(bottom = value)
            PaddingSide.LEFT -> copy(left = value)
            PaddingSide.RIGHT -> copy(right = value)
        }
}

data class PaddingSnapshot(val regions: Map<PaddingRegion, RegionPadding>) {
    operator fun get(region: PaddingRegion) = regions.getValue(region)
}

interface PaddingSettingsRepository {
    fun load(): PaddingSnapshot

    fun apply(region: PaddingRegion, side: PaddingSide, value: Int, linkSides: Boolean)

    fun setShowLine(region: PaddingRegion, shown: Boolean)

    fun reset(region: PaddingRegion)

    fun save()
}

internal fun paddingRegionEvents(region: PaddingRegion) =
    if (region == PaddingRegion.BODY) arrayListOf(10, 5) else arrayListOf(2)

class AppPaddingSettingsRepository : PaddingSettingsRepository {
    override fun load() =
        PaddingSnapshot(
            mapOf(
                PaddingRegion.HEADER to
                    RegionPadding(
                        ReadBookConfig.headerPaddingTop,
                        ReadBookConfig.headerPaddingBottom,
                        ReadBookConfig.headerPaddingLeft,
                        ReadBookConfig.headerPaddingRight,
                        ReadBookConfig.showHeaderLine,
                    ),
                PaddingRegion.BODY to
                    RegionPadding(
                        ReadBookConfig.paddingTop,
                        ReadBookConfig.paddingBottom,
                        ReadBookConfig.paddingLeft,
                        ReadBookConfig.paddingRight,
                    ),
                PaddingRegion.FOOTER to
                    RegionPadding(
                        ReadBookConfig.footerPaddingTop,
                        ReadBookConfig.footerPaddingBottom,
                        ReadBookConfig.footerPaddingLeft,
                        ReadBookConfig.footerPaddingRight,
                        ReadBookConfig.showFooterLine,
                    ),
            )
        )

    override fun apply(region: PaddingRegion, side: PaddingSide, value: Int, linkSides: Boolean) {
        setPadding(region, side, value)
        if (linkSides && (side == PaddingSide.LEFT || side == PaddingSide.RIGHT)) {
            setPadding(
                region,
                if (side == PaddingSide.LEFT) PaddingSide.RIGHT else PaddingSide.LEFT,
                value,
            )
        }
        postEvent(EventBus.UP_CONFIG, paddingRegionEvents(region))
    }

    private fun setPadding(region: PaddingRegion, side: PaddingSide, value: Int) {
        when (region) {
            PaddingRegion.HEADER ->
                when (side) {
                    PaddingSide.TOP -> ReadBookConfig.headerPaddingTop = value
                    PaddingSide.BOTTOM -> ReadBookConfig.headerPaddingBottom = value
                    PaddingSide.LEFT -> ReadBookConfig.headerPaddingLeft = value
                    PaddingSide.RIGHT -> ReadBookConfig.headerPaddingRight = value
                }
            PaddingRegion.BODY ->
                when (side) {
                    PaddingSide.TOP -> ReadBookConfig.paddingTop = value
                    PaddingSide.BOTTOM -> ReadBookConfig.paddingBottom = value
                    PaddingSide.LEFT -> ReadBookConfig.paddingLeft = value
                    PaddingSide.RIGHT -> ReadBookConfig.paddingRight = value
                }
            PaddingRegion.FOOTER ->
                when (side) {
                    PaddingSide.TOP -> ReadBookConfig.footerPaddingTop = value
                    PaddingSide.BOTTOM -> ReadBookConfig.footerPaddingBottom = value
                    PaddingSide.LEFT -> ReadBookConfig.footerPaddingLeft = value
                    PaddingSide.RIGHT -> ReadBookConfig.footerPaddingRight = value
                }
        }
    }

    override fun setShowLine(region: PaddingRegion, shown: Boolean) {
        when (region) {
            PaddingRegion.HEADER -> ReadBookConfig.showHeaderLine = shown
            PaddingRegion.FOOTER -> ReadBookConfig.showFooterLine = shown
            PaddingRegion.BODY -> return
        }
        postEvent(EventBus.UP_CONFIG, paddingRegionEvents(region))
    }

    override fun reset(region: PaddingRegion) {
        val defaults = ReadBookConfig.Config()
        val values =
            when (region) {
                PaddingRegion.HEADER ->
                    RegionPadding(
                        defaults.headerPaddingTop,
                        defaults.headerPaddingBottom,
                        defaults.headerPaddingLeft,
                        defaults.headerPaddingRight,
                    )
                PaddingRegion.BODY ->
                    RegionPadding(
                        defaults.paddingTop,
                        defaults.paddingBottom,
                        defaults.paddingLeft,
                        defaults.paddingRight,
                    )
                PaddingRegion.FOOTER ->
                    RegionPadding(
                        defaults.footerPaddingTop,
                        defaults.footerPaddingBottom,
                        defaults.footerPaddingLeft,
                        defaults.footerPaddingRight,
                    )
            }
        PaddingSide.entries.forEach { setPadding(region, it, values[it]) }
        postEvent(EventBus.UP_CONFIG, paddingRegionEvents(region))
    }

    override fun save() {
        ReadBookConfig.save()
    }
}
