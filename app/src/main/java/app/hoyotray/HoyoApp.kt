package app.hoyotray

import android.app.Application
import app.hoyotray.data.Repo
import app.hoyotray.work.Background

class HoyoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Background.createChannels(this)
        Background.schedule(this)
    }
}
