package app.fineprint

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FineprintApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Checker.init(this)
    }
}

object Prefs {
    private lateinit var prefs: SharedPreferences
    private val modelState = MutableStateFlow(Model.Base)
    private val autoState = MutableStateFlow(true)
    val model: StateFlow<Model> = modelState
    val auto: StateFlow<Boolean> = autoState

    fun init(context: Context) {
        prefs = context.getSharedPreferences("fineprint", Context.MODE_PRIVATE)
        modelState.value = Model.valueOf(prefs.getString("model", null) ?: Model.Base.name)
        autoState.value = prefs.getBoolean("auto", true)
    }

    fun setModel(model: Model) {
        modelState.value = model
        prefs.edit { putString("model", model.name) }
    }

    fun setAuto(auto: Boolean) {
        autoState.value = auto
        prefs.edit { putBoolean("auto", auto) }
    }
}
