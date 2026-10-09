package com.cocateail.maya

import android.app.Application
import com.cocateail.maya.data.db.AppDatabase
import com.cocateail.maya.data.repository.MayaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MayaApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
    val repository: MayaRepository by lazy {
        MayaRepository(
            context = this,
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            tessaTaskDao = database.tessaTaskDao()
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appScope.launch {
            com.cocateail.maya.data.model.ModelDownloadManager.initAndEnqueueDownload(this@MayaApplication)
        }
    }

    companion object {
        lateinit var instance: MayaApplication
            private set
    }
}
