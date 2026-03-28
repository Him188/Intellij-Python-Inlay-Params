package space.whitememory.pythoninlayparams

import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.utils.inlays.InlayHintsProviderTestCase
import com.jetbrains.python.psi.LanguageLevel
import space.whitememory.pythoninlayparams.python.PyLightProjectDescriptor
import java.nio.file.Files
import java.nio.file.Path


abstract class PythonAbstractInlayHintsTestCase : InlayHintsProviderTestCase() {
    companion object {
        init {
            if (System.getProperty("idea.python.helpers.path") == null) {
                val homePath = Path.of(PathManager.getHomePath())
                val candidates = listOf(
                    homePath.resolve("plugins/python-ce/helpers"),
                    homePath.resolve("plugins/python/helpers"),
                    homePath.resolve("python/helpers"),
                )
                candidates.firstOrNull(Files::isDirectory)?.let {
                    System.setProperty("idea.python.helpers.path", it.toString())
                }
            }
        }
    }

    override fun getProjectDescriptor() = PyLightProjectDescriptor(LanguageLevel.getLatest())
}
