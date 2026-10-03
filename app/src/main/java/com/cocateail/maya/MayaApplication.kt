package com.cocateail.maya

import android.app.Application
import com.cocateail.maya.data.db.AppDatabase
import com.cocateail.maya.data.repository.MayaRepository

class MayaApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
    val repository: MayaRepository by lazy { MayaRepository(this, database.conversationDao(), database.messageDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.cocateail.maya.data.model.ModelDownloadManager.initAndEnqueueDownload(this)
    }

    companion object {
        lateinit var instance: MayaApplication
            private set
    }
}
