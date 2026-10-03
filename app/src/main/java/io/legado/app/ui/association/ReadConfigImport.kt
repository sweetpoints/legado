package io.legado.app.ui.association

import io.legado.app.data.association.applyAssociationReadConfig
import io.legado.app.help.config.ReadBookConfig

/** Retains the existing transaction helper API while its rollback algorithm lives in data. */
internal fun applyImportedReadConfig(
    configs: MutableList<ReadBookConfig.Config>,
    imported: ReadBookConfig.Config,
    defaultConfigs: () -> List<ReadBookConfig.Config>,
    save: () -> Unit,
    transactionLock: Any = configs,
): String = applyAssociationReadConfig(configs, imported, defaultConfigs, save, transactionLock)
