package com.layerbit.deja

import android.app.Application
import com.layerbit.deja.data.ShotRepository
import com.layerbit.deja.data.index.IndexingState
import com.layerbit.deja.data.index.ScanPreferences
import com.layerbit.deja.ambient.NewScreenshotWorker
import com.layerbit.deja.util.CrashLog

class DejaApplication : Application() {

    val repository: ShotRepository by lazy { ShotRepository(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Whether a scan was left unfinished only lives on disk. Seeding it here is what lets the
        // UI keep offering Resume after a restart, rather than losing the fact along with the
        // process that knew about it.
        IndexingState.seedIncomplete(ScanPreferences(this).interrupted)
        // Re-arms the new-screenshot watch after an update or a reboot. Does nothing unless the
        // user switched it on.
        NewScreenshotWorker.schedule(this)
    }
}
