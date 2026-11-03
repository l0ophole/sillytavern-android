package app.sillytavern.android.extensions

import app.cash.quickjs.QuickJs
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickJsProbeTest {
    interface Bridge { fun call(name: String, args: String): String }
    interface Dispatcher { fun dispatch(kind: String, json: String): String }

    @Test fun roundTrip() {
        QuickJs.create().use { js ->
            js.set("__host", Bridge::class.java, object : Bridge { override fun call(name: String, args: String) = "$name:$args" })
            js.evaluate("var __d = { dispatch: function(k, j) { return __host.call(k, j) + \"!\"; } };")
            val d = js.get("__d", Dispatcher::class.java)
            assertEquals("a:b!", d.dispatch("a", "b"))
        }
    }
}
