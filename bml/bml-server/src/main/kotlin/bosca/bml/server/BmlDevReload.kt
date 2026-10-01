package bosca.bml.server

/**
 * Dev-mode live reload (outer loop). The server exposes an SSE endpoint that
 * announces a **process + generation id** on every connection; the injected client reloads the
 * page when Gradle publishes a newly compiled generation or when it reconnects after a process
 * restart. A plain timeout reconnect to an unchanged generation is a no-op.
 *
 * Pure helpers (no server deps) so the protocol is unit-testable; [BmlServer] wires them onto
 * the router + page responses when `dev = true`.
 */
object BmlDevReload {
    /** SSE endpoint path (namespaced under `/_bml/` to avoid colliding with page routes). */
    const val ENDPOINT: String = "/_bml/reload"

    /**
     * The named heartbeat event. A NAMED event fires only `addEventListener("ping")` listeners,
     * never the client's `onmessage`, so heartbeats can't be mistaken for a boot id.
     */
    const val HEARTBEAT_EVENT: String = "ping"

    /**
     * Client script: reload when SSE announces a different process or application generation.
     *
     * Visibility-aware: the stream is held ONLY while the tab is visible. Browsers allow ~6
     * connections per origin shared across every tab of the profile, so a handful of forgotten
     * site tabs each pinning a reload stream starves the one tab being clicked — navigations
     * stall for seconds waiting on a free socket. A hidden tab closes its stream (releasing the
     * socket) and reconnects on refocus; the current token sent on reconnect still triggers the
     * reload if a swap happened while the tab was hidden. `pagehide`/`pageshow` cover bfcache
     * traversals, where scripts don't re-run.
     */
    fun clientScript(): String = """
        <script>
        (function () {
          var booted = null;
          var es = null;
          function connect() {
            if (es) return;
            es = new EventSource("$ENDPOINT");
            es.onmessage = function (e) {
              if (booted === null) booted = e.data;
              else if (e.data !== booted) location.reload();
            };
          }
          function disconnect() {
            if (!es) return;
            es.close();
            es = null;
          }
          document.addEventListener("visibilitychange", function () {
            if (document.visibilityState === "hidden") disconnect();
            else connect();
          });
          window.addEventListener("pagehide", disconnect);
          window.addEventListener("pageshow", function () {
            if (document.visibilityState !== "hidden") connect();
          });
          if (document.visibilityState !== "hidden") connect();
        })();
        </script>
    """.trimIndent()

    /** Insert the live-reload script just before the last `</body>`, or append it if there is none. */
    fun inject(html: String, script: String = clientScript()): String {
        val idx = html.lastIndexOf("</body>")
        return if (idx >= 0) html.substring(0, idx) + script + "\n" + html.substring(idx) else html + "\n" + script
    }
}
