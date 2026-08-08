package recloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OlehdtvPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(OlehdtvProvider())
    }
}
