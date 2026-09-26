package dev.stackward.logs

/**
 * Built-in sample logs for trying the analyzer without SSH or a live server.
 */
enum class SampleLogFixture(
    val id: String,
    val title: String,
    val description: String,
) {
    NGINX_502(
        id = "nginx-502",
        title = "nginx 502 upstream",
        description = "systemd journal — reverse proxy cannot reach backend",
    ),
    DOCKER_OOM(
        id = "docker-oom",
        title = "Docker OOM restart loop",
        description = "container killed by cgroup memory limit, keeps restarting",
    ),
    POSTGRES_DISK(
        id = "postgres-disk",
        title = "PostgreSQL disk full",
        description = "database cannot write WAL; connection errors follow",
    ),
    ;

    val content: String
        get() = when (this) {
            NGINX_502 -> NGINX_502_LOG
            DOCKER_OOM -> DOCKER_OOM_LOG
            POSTGRES_DISK -> POSTGRES_DISK_LOG
        }

    companion object {
        fun fromId(id: String): SampleLogFixture? = entries.find { it.id == id }

        private val NGINX_502_LOG = """
Aug 08 14:32:01 web01 systemd[1]: Starting nginx.service...
Aug 08 14:32:01 web01 nginx[842]: nginx/1.24.0 starting
Aug 08 14:32:15 web01 nginx[842]: 2026/08/08 14:32:15 [error] 842#842: *17 connect() failed (111: Connection refused) while connecting to upstream, client: 10.0.0.4, server: app.example.com, request: "GET /api/health HTTP/1.1", upstream: "http://127.0.0.1:8080/api/health", host: "app.example.com"
Aug 08 14:32:15 web01 nginx[842]: 10.0.0.4 - - [08/Aug/2026:14:32:15 +0000] "GET /api/health HTTP/1.1" 502 157 "-" "curl/8.5.0"
Aug 08 14:32:16 web01 nginx[842]: 2026/08/08 14:32:16 [error] 842#842: *19 upstream prematurely closed connection while reading response header from upstream, client: 10.0.0.4, server: app.example.com, request: "GET /dashboard HTTP/1.1", upstream: "http://127.0.0.1:8080/dashboard", host: "app.example.com"
Aug 08 14:32:16 web01 nginx[842]: 10.0.0.4 - - [08/Aug/2026:14:32:16 +0000] "GET /dashboard HTTP/1.1" 502 157 "-" "Mozilla/5.0"
Aug 08 14:32:20 web01 systemd[1]: app-backend.service: Main process exited, code=exited, status=1/FAILURE
Aug 08 14:32:20 web01 systemd[1]: app-backend.service: Failed with result 'exit-code'.
Aug 08 14:32:20 web01 systemd[1]: app-backend.service: Scheduled restart job, restart counter is at 3.
        """.trimIndent()

        private val DOCKER_OOM_LOG = """
2026-08-08T14:10:02.113Z app-1  | INFO  Starting worker pid=1
2026-08-08T14:10:45.882Z app-1  | WARN  Heap usage at 92%
2026-08-08T14:11:03.004Z app-1  | ERROR java.lang.OutOfMemoryError: Java heap space
2026-08-08T14:11:03.441Z dockerd | time="2026-08-08T14:11:03Z" level=error msg="container app-1 OOMKilled"
2026-08-08T14:11:04.002Z dockerd | time="2026-08-08T14:11:04Z" level=warning msg="Restarting container app-1"
2026-08-08T14:11:34.771Z app-1  | INFO  Starting worker pid=1
2026-08-08T14:12:01.229Z app-1  | ERROR Fatal error: cannot allocate buffer
2026-08-08T14:12:01.550Z dockerd | time="2026-08-08T14:12:01Z" level=error msg="container app-1 exited with code 137"
2026-08-08T14:12:02.010Z dockerd | time="2026-08-08T14:12:02Z" level=warning msg="Restarting container app-1 (crash loop)"
        """.trimIndent()

        private val POSTGRES_DISK_LOG = """
2026-08-08 15:04:11 UTC [12844]: PANIC:  could not write to file "pg_wal/xlogtemp.12844": No space left on device
2026-08-08 15:04:11 UTC [12844]: LOG:  server process (PID 12844) was terminated by signal 6: Aborted
2026-08-08 15:04:12 UTC [1]: LOG:  terminating all remaining server processes
2026-08-08 15:04:12 UTC [12801]: FATAL:  the database system is in recovery mode
2026-08-08 15:04:13 UTC [12855]: FATAL:  could not connect to the primary server: connection refused
2026-08-08 15:04:15 UTC api[902]: ERROR database query failed: FATAL:  the database system is not yet accepting connections
2026-08-08 15:04:15 UTC api[902]: ERROR GET /api/users returned 500 internal server error
2026-08-08 15:04:16 UTC api[902]: ERROR GET /api/orders returned 503 service unavailable
        """.trimIndent()
    }
}
