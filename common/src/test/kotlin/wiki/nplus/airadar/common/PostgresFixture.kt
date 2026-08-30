package wiki.nplus.airadar.common

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import javax.sql.DataSource

object PostgresFixture {

    val available: Boolean by lazy {
        try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (e: Throwable) {
            false
        }
    }

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17")
            .withDatabaseName("airadar")
            .withUsername("airadar")
            .withPassword("airadar")
            .also { it.start() }
    }

    val dataSource: DataSource by lazy {
        val ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                poolName = "bookshelf-echo-test"
                maximumPoolSize = 2
            },
        )
        migrate(ds)
        ds
    }

    val repo: ItemRepository by lazy { ItemRepository(dataSource) }

    private fun migrate(ds: DataSource) {
        val dir = findMigrationsDir()
        val files = dir.listFiles { f: File -> f.name.endsWith(".sql") }
            ?.sortedBy { it.name.substringBefore("__").removePrefix("V").toInt() }
            ?: error("no migrations in $dir")
        ds.connection.use { c ->
            files.forEach { f ->
                c.createStatement().use { st -> st.execute(f.readText()) }
            }
        }
    }

    private fun findMigrationsDir(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            val candidate = File(d, "db/migrations")
            if (candidate.isDirectory) return candidate
            d = d.parentFile
        }
        error("db/migrations not found above ${File("").absolutePath}")
    }

    fun reset() {
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    TRUNCATE items, item_contents, digests, matches, shortlist, selection_runs,
                             essays, llm_usage, publish_log, metrics_snapshots
                    RESTART IDENTITY CASCADE
                    """.trimIndent(),
                )
            }
        }
    }
}
