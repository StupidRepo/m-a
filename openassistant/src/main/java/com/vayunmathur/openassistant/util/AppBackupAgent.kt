package com.vayunmathur.openassistant.util

import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.library.util.DatabaseHelper
import java.io.File

class AppBackupAgent : BaseBackupAgent() {
    override val dbConfigs: List<Pair<String, String>>
        get() {
            val helper = DatabaseHelper(this)
            return if (helper.isKeyGenerated()) {
                try {
                    val pass = helper.getPassphrase()
                    listOf("passwords-db" to pass)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }

    override val extraFiles: List<File>
        get() = emptyList()
}
