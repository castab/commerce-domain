package io.github.castab.commerce.runtime

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.sseHandshake
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.session.BearerSessionToken
import io.github.castab.commerce.runtime.session.sessionAuthentication
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.insertTestPrincipal
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.lens.Path
import org.http4k.lens.uuid
import org.http4k.routing.bind
import org.http4k.sse.Sse
import org.http4k.sse.SseEventId
import org.http4k.sse.SseMessage
import org.http4k.sse.SseResponse
import java.io.BufferedReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import org.http4k.routing.sse.bind as sseBind

class NativeSseSpec :
    FunSpec({
        test("failed startup with an active stream quiesces admission and preserves cleanup failures") {
            TestDatabase.create().use { database ->
                withSseClient { client ->
                    val closed = LinkedBlockingQueue<Unit>()
                    val activated = LinkedBlockingQueue<Unit>()
                    lateinit var runtime: CommerceRuntime
                    lateinit var context: CommerceRuntimeContext
                    var connected: HttpResponse<java.io.InputStream>? = null
                    runtime =
                        commerceRuntime(
                            database.sseConfiguration(),
                            ApplicationContributions(
                                sseRoutes = {
                                    listOf(
                                        "/example/updates" sseBind { _: org.http4k.core.Request ->
                                            SseResponse { stream ->
                                                stream.onClose { closed.add(Unit) }
                                                activated.add(Unit)
                                            }
                                        },
                                    )
                                },
                                backgroundServices =
                                    listOf({ ctx ->
                                        context = ctx
                                        object : ManagedBackgroundService {
                                            override fun start() {
                                                connected = client.openStream(runtime, "/example/updates")
                                                activated.poll(5, SECONDS) shouldBe Unit
                                                error("start failed")
                                            }

                                            override fun close() {
                                                client.get(runtime, "/health").statusCode() shouldBe 503
                                                context.transactor.inTransaction { it.handle.execute("SELECT 1") }
                                                error("cleanup failed")
                                            }
                                        }
                                    }),
                            ),
                        )
                    val failure = CompletableFuture.supplyAsync { runCatching { runtime.start() }.exceptionOrNull() }.get(10, SECONDS)!!
                    failure.message shouldBe "start failed"
                    failure.suppressed.single().message shouldBe "cleanup failed"
                    connected!!.body().bufferedReader().use { it.end() shouldBe null }
                    closed.poll(5, SECONDS) shouldBe Unit
                    shouldThrow<Exception> { context.transactor.inTransaction { it.handle.execute("SELECT 1") } }
                    runtime.close()
                }
            }
        }

        test("handshake headers and short circuits survive fallback and post-commit exceptions close the stream") {
            TestDatabase.create().use { database ->
                val closed = LinkedBlockingQueue<Unit>()
                val activated = AtomicInteger()
                val reject = Filter { _ -> { Response(Status.FORBIDDEN).header("X-Handshake", "rejected").body("application rejection") } }
                val runtime =
                    commerceRuntime(
                        database.sseConfiguration(),
                        ApplicationContributions(
                            sseRoutes = {
                                listOf(
                                    "/health" sseBind { _: org.http4k.core.Request -> error("health must remain HTTP") },
                                    "/ready" sseBind { _: org.http4k.core.Request -> error("ready must remain HTTP") },
                                    "/denied" sseBind sseHandshake(reject) { SseResponse { activated.incrementAndGet() } },
                                    "/empty" sseBind
                                        sseHandshake(Filter { _ -> { Response(Status.NO_CONTENT) } }) { error("must not prepare") },
                                    "/native-denied" sseBind
                                        { _: org.http4k.core.Request -> SseResponse(Status.UNAUTHORIZED) { activated.incrementAndGet() } },
                                    "/broken-consumer" sseBind { _: org.http4k.core.Request ->
                                        SseResponse { stream ->
                                            stream.onClose { closed.add(Unit) }
                                            error("private post-commit failure")
                                        }
                                    },
                                )
                            },
                        ),
                    )
                runtime.use {
                    runtime.start()
                    withSseClient { client ->
                        client.get(runtime, "/health", accept = true).statusCode() shouldBe 200
                        client.get(runtime, "/ready", accept = true).statusCode() shouldBe 200
                        client.get(runtime, "/denied", accept = true).let {
                            it.statusCode() shouldBe 403
                            it.headers().firstValue("X-Handshake").orElse("") shouldBe "rejected"
                            it.body() shouldBe "application rejection"
                        }
                        client.get(runtime, "/empty", accept = true).statusCode() shouldBe 204
                        client.get(runtime, "/native-denied", accept = true).let {
                            it.statusCode() shouldBe 401
                            CommerceJson.asA(it.body(), ErrorResponse::class).code shouldBe "unauthenticated"
                        }
                        activated.get() shouldBe 0
                        client.openStream(runtime, "/broken-consumer").let {
                            it.statusCode() shouldBe 200
                            it.body().bufferedReader().use { body -> body.end() shouldBe null }
                        }
                        closed.poll(5, SECONDS) shouldBe Unit
                    }
                }
            }
        }

        test("SSE factory failure closes the shared pool without constructing services") {
            TestDatabase.create().use { database ->
                lateinit var context: CommerceRuntimeContext
                val failure =
                    shouldThrow<IllegalStateException> {
                        commerceRuntime(
                            database.sseConfiguration(),
                            ApplicationContributions(
                                sseRoutes = { ctx ->
                                    context = ctx
                                    error("SSE factory failed")
                                },
                                backgroundServices = listOf({ error("must not construct") }),
                            ),
                        )
                    }
                failure.message shouldBe "SSE factory failed"
                shouldThrow<Exception> { context.transactor.inTransaction { it.handle.execute("SELECT 1") } }
            }
        }

        test("a socket that stops reading cannot stall HTTP or another stream and its writer stops before Jetty") {
            TestDatabase.create().use { database ->
                val streams = LinkedBlockingQueue<Sse>()
                var writer: Thread? = null
                val runtime =
                    commerceRuntime(
                        database.sseConfiguration(),
                        ApplicationContributions(
                            routes = { listOf("/ordinary" bind Method.GET to { Response(Status.OK) }) },
                            sseRoutes = {
                                listOf(
                                    "/example/updates" sseBind { _: org.http4k.core.Request -> SseResponse { streams.add(it) } },
                                )
                            },
                            backgroundServices =
                                listOf({ context ->
                                    object : ManagedBackgroundService {
                                        override fun start() = Unit

                                        override fun close() {
                                            writer?.interrupt()
                                            writer?.join(5_000)
                                            writer?.isAlive shouldBe false
                                            context.transactor.inTransaction { it.handle.execute("SELECT 1") }
                                        }
                                    }
                                }),
                        ),
                    )
                runtime.use {
                    runtime.start()
                    Socket().use { stalled ->
                        stalled.receiveBufferSize = 1024
                        stalled.soTimeout = 5_000
                        stalled.connect(InetSocketAddress("localhost", runtime.port()), 5_000)
                        stalled.getOutputStream().write(
                            "GET /example/updates HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n".toByteArray(),
                        )
                        val headers = StringBuilder()
                        while (!headers.endsWith("\r\n\r\n")) {
                            val byte = stalled.getInputStream().read()
                            check(byte >= 0)
                            headers.append(byte.toChar())
                        }
                        headers.toString() shouldContain "200"
                        val slow = streams.poll(5, SECONDS)!!
                        val blockedWriter = Thread.ofVirtual().start { slow.send(SseMessage.Data("x".repeat(32 * 1024 * 1024))) }
                        writer = blockedWriter
                        awaitSse {
                            blockedWriter.state == Thread.State.WAITING &&
                                blockedWriter.stackTrace.any { frame -> frame.className.startsWith("org.eclipse.jetty") }
                        }
                        withSseClient { client ->
                            client.openStream(runtime, "/example/updates").body().bufferedReader().use { reader ->
                                val healthy = streams.poll(5, SECONDS)!!
                                repeat(3) {
                                    listOf("/ordinary", "/health", "/ready").forEach { path ->
                                        client.get(runtime, path).statusCode() shouldBe
                                            200
                                    }
                                    healthy.send(SseMessage.Event("changed", "healthy"))
                                    reader.frame() shouldBe "event:changed\ndata:healthy"
                                }
                                CompletableFuture.runAsync { runtime.close() }.get(10, SECONDS)
                                reader.end() shouldBe null
                                runtime.close()
                            }
                        }
                    }
                }
            }
        }

        test("two native streams flush later events while ordinary traffic stays responsive and close terminates them") {
            TestDatabase.create().use { database ->
                val streams = LinkedBlockingQueue<Sse>()
                val closed = LinkedBlockingQueue<Unit>()
                var serviceClosed = false
                lateinit var context: CommerceRuntimeContext
                val runtime =
                    commerceRuntime(
                        database.sseConfiguration(),
                        ApplicationContributions(
                            routes = { ctx ->
                                context = ctx
                                listOf("/ordinary" bind Method.GET to { Response(Status.OK).body("ordinary") })
                            },
                            sseRoutes = { ctx ->
                                (ctx === context) shouldBe true
                                listOf(
                                    "/example/updates" sseBind { _: org.http4k.core.Request ->
                                        SseResponse { stream ->
                                            stream.onClose { closed.add(Unit) }
                                            streams.add(stream)
                                        }
                                    },
                                )
                            },
                            backgroundServices =
                                listOf({ ctx ->
                                    object : ManagedBackgroundService {
                                        override fun start() = Unit

                                        override fun close() {
                                            ctx.transactor.inTransaction { it.handle.execute("SELECT 1") }
                                            serviceClosed = true
                                        }
                                    }
                                }),
                        ),
                    )
                runtime.use {
                    runtime.start()
                    withSseClient { client ->
                        val first = client.openStream(runtime, "/example/updates")
                        val second = client.openStream(runtime, "/example/updates")
                        first.statusCode() shouldBe 200
                        first.headers().firstValue("Content-Type").orElse("") shouldBe "text/event-stream; charset=utf-8"
                        second.statusCode() shouldBe 200
                        first.body().bufferedReader().use { one ->
                            second.body().bufferedReader().use { two ->
                                val a = streams.poll(5, SECONDS)!!
                                val b = streams.poll(5, SECONDS)!!
                                repeat(5) { revision ->
                                    listOf("/ordinary", "/health", "/ready").forEach { path ->
                                        client.get(runtime, path).statusCode() shouldBe 200
                                        // An SSE Accept header on HTTP routes must still fall through.
                                        client.get(runtime, path, accept = true).statusCode() shouldBe 200
                                    }
                                    a.send(SseMessage.Event("changed", "first-$revision", SseEventId("$revision")))
                                    b.send(SseMessage.Event("changed", "second-$revision", SseEventId("$revision")))
                                    one.frame() shouldBe "id:$revision\nevent:changed\ndata:first-$revision"
                                    two.frame() shouldBe "id:$revision\nevent:changed\ndata:second-$revision"
                                }
                                a.close()
                                one.end() shouldBe null
                                closed.poll(5, SECONDS) shouldBe Unit
                                CompletableFuture.runAsync { runtime.close() }.get(10, SECONDS)
                                two.end() shouldBe null
                                closed.poll(5, SECONDS) shouldBe Unit
                                serviceClosed shouldBe true
                                runtime.close()
                                shouldThrow<Exception> { context.transactor.inTransaction { it.handle.execute("SELECT 1") } }
                            }
                        }
                    }
                }
            }
        }

        test("pre-stream failures retain HTTP errors and authenticate once without activating a consumer") {
            TestDatabase.create().use { database ->
                val handshakes = AtomicInteger()
                val consumers = AtomicInteger()
                val user = UserId(UUID.randomUUID())
                val forbidden = UserId(UUID.randomUUID())
                val role = RoleKey("stream-reader")
                val id = UUID.randomUUID()
                var token = ""
                var deniedToken = ""
                val runtime =
                    commerceRuntime(
                        database.sseConfiguration(),
                        ApplicationContributions(
                            sseRoutes = { context ->
                                context.transactor.insertTestPrincipal(user)
                                context.transactor.insertTestPrincipal(forbidden)
                                context.authorization.createRole(
                                    RoleDefinition(role, "Stream reader", null, setOf(CommercePermissions.FinancialDocumentRead)),
                                )
                                context.authorization.assignRole(user, role)
                                token =
                                    context.sessions
                                        .create(user)
                                        .token.value
                                deniedToken =
                                    context.sessions
                                        .create(forbidden)
                                        .token.value
                                val count =
                                    Filter { next ->
                                        { request ->
                                            // The combined Jetty sequence dispatches handshake I/O on Loom.
                                            Thread.currentThread().isVirtual shouldBe true
                                            handshakes.incrementAndGet()
                                            next(request)
                                        }
                                    }
                                val access =
                                    AccessControl(sessionAuthentication(context.sessions, BearerSessionToken), context.authorization)
                                val resource = Path.uuid().of("id")
                                listOf(
                                    "/example/{id}" sseBind
                                        sseHandshake(
                                            count.then(access.requirePermission(CommercePermissions.FinancialDocumentRead)),
                                        ) { request ->
                                            authenticatedPrincipal(request) shouldBe user
                                            val requested = resource(request)
                                            if (requested == UUID(0, 0)) error("secret database detail")
                                            if (requested != id) throw CommerceFailure.NotFound("Example does not exist")
                                            SseResponse { stream ->
                                                consumers.incrementAndGet()
                                                stream.close()
                                            }
                                        },
                                )
                            },
                        ),
                    )
                runtime.use {
                    runtime.start()
                    withSseClient { client ->
                        val cases =
                            listOf(
                                Triple("/example/$id", "", 401 to "unauthenticated"),
                                Triple("/example/$id", deniedToken, 403 to "forbidden"),
                                Triple("/example/not-a-uuid", token, 400 to "malformed_request"),
                                Triple("/example/${UUID.randomUUID()}", token, 404 to "not_found"),
                                Triple("/example/${UUID(0, 0)}", token, 500 to "internal_failure"),
                            )
                        cases.forEachIndexed { index, (path, credential, expected) ->
                            val response = client.get(runtime, path, credential, accept = true)
                            response.statusCode() shouldBe expected.first
                            response.headers().firstValue("Content-Type").orElse("") shouldContain "application/json"
                            CommerceJson.asA(response.body(), ErrorResponse::class).code shouldBe expected.second
                            response.body() shouldNotContain "secret database detail"
                            handshakes.get() shouldBe index + 1
                            consumers.get() shouldBe 0
                            // No rejection can bleed into the next request on a reused thread/connection.
                            client.get(runtime, "/health").statusCode() shouldBe 200
                        }
                        client.get(runtime, "/unknown", accept = true).let {
                            it.statusCode() shouldBe 404
                            CommerceJson.asA(it.body(), ErrorResponse::class).code shouldBe "not_found"
                        }
                        client.get(runtime, "/example/$id", token).statusCode() shouldBe 404
                        handshakes.get() shouldBe cases.size
                        val post =
                            HttpRequest
                                .newBuilder(URI("http://localhost:${runtime.port()}/example/$id"))
                                .timeout(
                                    Duration.ofSeconds(5),
                                ).header("Accept", "text/event-stream")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build()
                        client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 404
                        handshakes.get() shouldBe cases.size
                        client.openStream(runtime, "/example/$id", token).body().use { it.read() shouldBe -1 }
                        handshakes.get() shouldBe cases.size + 1
                        consumers.get() shouldBe 1
                    }
                }
            }
        }
    })

private fun TestDatabase.sseConfiguration() =
    CommerceRuntimeConfiguration(
        server = CommerceRuntimeConfiguration.Server(0),
        database = configuration,
        migrations = CommerceRuntimeConfiguration.Migrations(CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE),
    )

private fun HttpClient.openStream(
    runtime: CommerceRuntime,
    path: String,
    token: String = "",
) = sendAsync(request(runtime, path, token, true), HttpResponse.BodyHandlers.ofInputStream()).get(5, SECONDS)

private fun HttpClient.get(
    runtime: CommerceRuntime,
    path: String,
    token: String = "",
    accept: Boolean = false,
) = sendAsync(request(runtime, path, token, accept), HttpResponse.BodyHandlers.ofString()).get(5, SECONDS)

private fun request(
    runtime: CommerceRuntime,
    path: String,
    token: String,
    accept: Boolean,
): HttpRequest =
    HttpRequest
        .newBuilder(URI("http://localhost:${runtime.port()}$path"))
        .timeout(Duration.ofSeconds(5))
        .apply {
            if (accept) header("Accept", "text/event-stream")
            if (token.isNotEmpty()) header("Authorization", "Bearer $token")
        }.GET()
        .build()

private fun BufferedReader.frame(): String =
    CompletableFuture
        .supplyAsync {
            val lines = mutableListOf<String>()
            while (true) {
                val line = readLine() ?: error("Stream ended before event")
                if (line.isEmpty()) {
                    if (lines.isNotEmpty()) break
                } else {
                    lines.add(line)
                }
            }
            lines.joinToString("\n")
        }.get(5, SECONDS)

private fun BufferedReader.end(): String? = CompletableFuture.supplyAsync { readLine() }.get(5, SECONDS)

private fun awaitSse(condition: () -> Boolean) {
    val deadline = System.nanoTime() + SECONDS.toNanos(5)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "SSE condition did not become true" }
        LockSupport.parkNanos(1_000_000)
    }
}

private inline fun withSseClient(block: (HttpClient) -> Unit) {
    val client = HttpClient.newHttpClient()
    try {
        block(client)
    } finally {
        client.shutdownNow()
        check(client.awaitTermination(Duration.ofSeconds(5)))
    }
}
