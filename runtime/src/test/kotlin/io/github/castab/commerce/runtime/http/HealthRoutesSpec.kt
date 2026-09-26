package io.github.castab.commerce.runtime.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

class HealthRoutesSpec :
    FunSpec({
        test("GET /health answers ok without consulting readiness") {
            val routes = healthRoutes(ready = { error("liveness must not check dependencies") })

            val response = routes(Request(Method.GET, "/health"))

            response.status shouldBe Status.OK
            response.header("Content-Type") shouldBe "application/json; charset=utf-8"
            response.bodyString() shouldBe """{"status":"ok"}"""
        }

        test("GET /ready follows the readiness check") {
            healthRoutes(ready = { true })(Request(Method.GET, "/ready")).let {
                it.status shouldBe Status.OK
                it.bodyString() shouldBe """{"status":"ready"}"""
            }
            healthRoutes(ready = { false })(Request(Method.GET, "/ready")).let {
                it.status shouldBe Status.SERVICE_UNAVAILABLE
                it.bodyString() shouldBe """{"status":"unavailable"}"""
            }
        }
    })
