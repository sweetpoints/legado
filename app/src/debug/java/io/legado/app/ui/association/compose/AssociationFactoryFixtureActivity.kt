package io.legado.app.ui.association.compose

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import io.legado.app.ui.association.FileAssociationViewModel

/** Exercises child-first access to the installed concrete wrapper and its saved-state factory. */
class AssociationFactoryFixtureActivity : FragmentActivity() {
    var createdHandle: SavedStateHandle? = null
        private set

    val model: FileAssociationViewModel
        get() = ViewModelProvider(this)[FileAssociationViewModel::class.java]

    val childModel: FileAssociationViewModel
        get() =
            (supportFragmentManager.findFragmentByTag("factory-child")
                    as AssociationFactoryFixtureFragment)
                .model

    override val defaultViewModelCreationExtras: CreationExtras
        get() = associationCreationExtras(super.defaultViewModelCreationExtras)

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() =
            AssociationViewModelFactory(
                super.defaultViewModelProviderFactory,
                FileAssociationViewModel::class.java,
                { intent.getStringExtra("prepared") },
            ) { handle ->
                createdHandle = handle
                FileAssociationViewModel(application, handle)
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .add(AssociationFactoryFixtureFragment(), "factory-child")
                .commitNow()
        }
        // The child accesses the default factory before this Host asks for its own model.
        check(childModel === model)
        val ticket = intent.getStringExtra("prepared")
        intent =
            Intent(this, AssociationFactoryFixtureActivity::class.java).putExtra("prepared", ticket)
    }

    fun captureSavedState(): Bundle = Bundle().also { super.onSaveInstanceState(it) }
}

class AssociationFactoryFixtureFragment : Fragment() {
    val model by activityViewModels<FileAssociationViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model
    }
}
