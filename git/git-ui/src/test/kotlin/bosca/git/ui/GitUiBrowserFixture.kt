package bosca.git.ui

/** Isolated HTTP/API fixture for browser checks; it never accesses an ordinary repository. */
object GitUiBrowserFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        GitUiRoutesTest.start()
        Runtime.getRuntime().addShutdownHook(Thread { GitUiRoutesTest.stop() })
        println("GIT_UI_BASE_URL=http://localhost:${GitUiRoutesTest.browserFixturePort()}")
        java.util.concurrent.CountDownLatch(1).await()
    }
}
