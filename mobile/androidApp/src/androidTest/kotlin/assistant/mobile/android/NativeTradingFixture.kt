package assistant.mobile.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic plugin catalog and Bybit-shaped replies; no exchange or credentials are contacted. */
internal class NativeTradingFixture {
    @Volatile var connected = true
    @Volatile var compatible = true
    @Volatile var installationTitle = "Торговля"
    private var revision = 3
    private var publicSettings = JSONObject().put("environment", "mainnet").put("label", "Synthetic account")
    private val changes = LinkedBlockingQueue<String>()
    private val sequence = AtomicInteger()
    val activeStreams = AtomicInteger()
    @Volatile var totalUsd = "2520.5"
    /** Admission succeeds before these replies are deliberately lost or returned as 202. */
    @Volatile var lostResponseTools = emptySet<String>()
    @Volatile var pendingResponseTools = emptySet<String>()
    @Volatile var operationLookupOnline = true
    @Volatile var flowTreatment = "auto"
    @Volatile var flowUnitCost: String? = null
    @Volatile var strategyCoin: String? = null
    @Volatile var lotMethod = "lifo"
    private var core: JSONObject? = null
    private val operations = ConcurrentHashMap<String, JSONObject>()

    fun accepts(path: String): Boolean = path.substringBefore('?').let {
        it == "/v1/plugins" || it.startsWith("$BASE/")
    }

    fun isEvents(path: String): Boolean = path.substringBefore('?') == "$BASE/events"

    fun checkTransport(request: NativeLoopbackServer.Request) {
        check(request.headers["authorization"] == "Bearer ${NativeLoopbackServer.credential(request.userId())}")
        check(!request.headers.containsKey("cookie"))
        if (request.method != "GET") check(request.headers["x-expected-user"] == request.userId())
    }

    @Synchronized fun respond(request: NativeLoopbackServer.Request): Reply {
        checkTransport(request)
        return when {
            request.path == "/v1/plugins" && request.method == "GET" -> json(catalog())
            request.path == "$BASE/invoke" && request.method == "POST" -> invoke(request)
            request.path.startsWith("$BASE/operations/by-key/") && request.method == "GET" -> {
                if (!operationLookupOnline) json(JSONObject().put("error", "temporarily_unavailable"), 503)
                else operations[request.path.substringAfterLast('/')]?.let { json(it) }
                    ?: json(JSONObject().put("error", "not_found"), 404)
            }
            request.path == "$BASE/configuration" && request.method == "GET" -> json(configuration())
            request.path == "$BASE/configuration" && request.method == "PUT" -> {
                check(request.headers["idempotency-key"]?.isNotBlank() == true)
                check(request.headers["if-match"] == if (connected) "\"$revision\"" else null)
                val body = request.json()
                check(body.getJSONObject("secret").getString("api_key") == SYNTHETIC_KEY)
                check(body.getJSONObject("secret").getString("api_secret") == SYNTHETIC_SECRET)
                publicSettings = JSONObject(body.getJSONObject("public").toString()).also {
                    if (!it.has("environment")) it.put("environment", "mainnet")
                }
                revision = if (connected) revision + 1 else 1
                connected = true
                json(result(configuration()))
            }
            request.path.substringBefore('?') == "$BASE/configuration" && request.method == "DELETE" -> {
                check(request.headers["if-match"] == "\"$revision\"")
                connected = false
                revision += 1
                json(result(configuration()))
            }
            else -> error("Unknown synthetic trading route")
        }
    }

    /** An event changes the read model; it contains no connection credentials. */
    fun portfolioChanged(value: String) {
        totalUsd = value
        val cursor = "trading-${sequence.incrementAndGet()}"
        val event = JSONObject().put("event_id", cursor).put("installation_id", INSTALLATION_ID)
            .put("type", "trading.data.changed").put("occurred_at", AS_OF).put("cursor", cursor)
            .put("payload", JSONObject().put("domains", JSONArray(listOf("wallet"))))
        changes.put("event: trading.data.changed\ndata: $event\n\n")
    }

    fun events(output: OutputStream, request: NativeLoopbackServer.Request) {
        checkTransport(request)
        activeStreams.incrementAndGet()
        try {
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            val cursor = request.headers["last-event-id"] ?: "trading-0"
            output.write("event: stream.ready\ndata: {\"cursor\":\"$cursor\"}\n\n".toByteArray())
            output.flush()
            while (!Thread.currentThread().isInterrupted) {
                output.write((changes.poll(100, TimeUnit.MILLISECONDS) ?: ": synthetic heartbeat\n\n").toByteArray())
                output.flush()
            }
        } finally {
            activeStreams.decrementAndGet()
        }
    }

    private fun catalog(): JSONObject {
        val required = JSONObject()
        READ_TOOLS.forEach { required.put(it, major(it)) }
        val tools = JSONArray()
        (READ_TOOLS + WRITE_TOOLS).forEach { id ->
            tools.put(JSONObject().put("id", id).put("version", "${major(id)}.0.0")
                .put("effect", if (id in READ_TOOLS) "read" else "write")
                .put("scopes", JSONArray(listOf(if (id in READ_TOOLS) "trading.read" else "trading.write")))
                .put("surfaces", JSONArray(listOf("native"))))
        }
        return JSONObject().put("installations", JSONArray().put(JSONObject()
            .put("installation_id", INSTALLATION_ID).put("package", JSONObject().put("id", "trading").put("version", "1.0.0"))
            .put("title", installationTitle).put("status", "ready")
            .put("granted_scopes", JSONArray(listOf("trading.read", "trading.write")))
            .put("tools", tools).put("configuration", JSONObject().put("writable", true))
            .put("events", JSONArray(listOf("trading.data.changed", "trading.connection.changed").map { JSONObject().put("type", it) }))
            .put("ui", JSONArray().put(JSONObject().put("id", "trading.home").put("kind", "native")
                .put("entry", "trading.home").put("title", "Торговля").put("client_api", if (compatible) 1 else 2)
                .put("requires_tools", required)))))
    }

    private fun invoke(request: NativeLoopbackServer.Request): Reply {
        val body = request.json()
        val tool = body.getString("tool_id")
        check(body.getString("tool_version") == "${major(tool)}.0.0")
        check(tool in READ_TOOLS || tool in WRITE_TOOLS)
        if (tool in WRITE_TOOLS) return write(request, tool, body.getJSONObject("arguments"))
        if (!connected) return json(JSONObject().put("contract", "assistant.result/v1")
            .put("operation_key", "synthetic-read").put("state", "failed")
            .put("error", JSONObject().put("code", "not_configured").put("message", "Ключ Bybit не подключён").put("recovery", "none"))
            .put("receipts", JSONArray()).put("effects", JSONArray()))
        val data = when (tool) {
            "portfolio.get" -> portfolio()
            "portfolio.history" -> history()
            "positions.list" -> positions()
                .put("unit", body.getJSONObject("arguments").optString("unit", strategyCoin ?: "USD"))
                .put("lot_method", body.getJSONObject("arguments").optString("method", lotMethod))
            "position.summary" -> {
                check(body.getJSONObject("arguments").getString("coin") == "BTC")
                position().put("unit", body.getJSONObject("arguments").optString("unit", strategyCoin ?: "USD"))
                    .put("lot_method", body.getJSONObject("arguments").optString("method", lotMethod)).put("as_of", JSONObject.NULL)
                    .put("open_cost", "1200").put("opening", JSONObject().put("qty", "0").put("at", "2026-09-01T10:00:00Z"))
                    .put("away_qty", "0").put("short_qty", "0").put("unpriced_events", 0).put("events", 2)
                    .put("lots", JSONArray().put(JSONObject().put("qty", "0.02").put("unit_cost", "60000").put("core", false)))
                    .put("lots_total", 1).put("realized_by_month", JSONArray()).put("freshness", freshness())
            }
            "position.chart" -> chart()
            "orders.open" -> orders(history = false)
            "orders.history" -> orders(history = true)
            "executions.list" -> executions()
            "cashflow.list" -> cashflow()
            "flows.list" -> flows()
            "symbols.list" -> JSONObject().put("items", JSONArray().put(JSONObject().put("symbol", "BTCUSDT")
                .put("base_coin", "BTC").put("quote_coin", "USDT"))).put("freshness", freshness())
            "sync.status" -> JSONObject().put("connection", JSONObject().put("state", "active")
                .put("environment", "mainnet").put("api_key_hint", "••••fixture"))
                .put("stream", JSONObject().put("state", "live")).put("domains", JSONArray().put(JSONObject()
                    .put("domain", "executions").put("phase", "idle").put("watermark_at", AS_OF)
                    .put("progress_pct", "100"))).put("freshness", freshness())
            else -> JSONObject().put("items", JSONArray()).put("next_cursor", JSONObject.NULL).put("freshness", freshness())
        }
        return json(result(data))
    }

    private fun write(request: NativeLoopbackServer.Request, tool: String, args: JSONObject): Reply {
        val key = requireNotNull(request.headers["idempotency-key"]).also { check(it.isNotBlank()) }
        check(!operations.containsKey(key)) { "A journaled write was posted twice" }
        val data = when (tool) {
            "flows.mark" -> {
                check(args.getString("id") == FLOW_ID)
                flowTreatment = args.getString("treatment")
                check(flowTreatment in setOf("auto", "own_wallet", "external"))
                flowUnitCost = if (args.has("unit_cost_usd")) args.getString("unit_cost_usd") else null
                check(flowTreatment == "external" || flowUnitCost == null)
                JSONObject().put("id", FLOW_ID).put("treatment", flowTreatment)
                    .put("unit_cost_usd", flowUnitCost ?: JSONObject.NULL)
            }
            "positions.core" -> {
                check(args.getString("coin") == "BTC")
                core = if (args.isNull("qty")) null else JSONObject().put("qty", args.getString("qty"))
                    .put("since", args.getString("since"))
                JSONObject().put("coin", "BTC").put("core", core ?: JSONObject.NULL)
            }
            "positions.strategy" -> {
                strategyCoin = if (args.isNull("coin")) null else args.getString("coin")
                JSONObject().put("strategy_coin", strategyCoin ?: JSONObject.NULL)
            }
            "positions.method" -> {
                lotMethod = args.getString("method").also { check(it in setOf("lifo", "fifo")) }
                JSONObject().put("lot_method", lotMethod)
            }
            "sync.refill" -> {
                check(args.length() == 0)
                JSONObject().put("domains", JSONArray().put(JSONObject().put("domain", "executions").put("watermark_at", AS_OF)))
            }
            else -> error("Unknown synthetic trading write")
        }
        val completed = result(data).put("operation_key", key).put("receipts", JSONArray().put(JSONObject()
            .put("id", "synthetic-trading-receipt-$key").put("kind", tool)))
        operations[key] = completed
        return when (tool) {
            in lostResponseTools -> Reply(0, "")
            in pendingResponseTools -> json(JSONObject().put("contract", "assistant.result/v1")
                .put("operation_key", key).put("state", "running").put("receipts", JSONArray()).put("effects", JSONArray()), 202)
            else -> json(completed)
        }
    }

    private fun orders(history: Boolean): JSONObject = JSONObject().put("items", JSONArray().put(JSONObject()
        .put("order_id", if (history) HISTORY_ORDER_ID else OPEN_ORDER_ID).put("symbol", "BTCUSDT")
        .put("base_coin", "BTC").put("quote_coin", "USDT").put("side", if (history) "sell" else "buy")
        .put("type", "Limit").put("status", if (history) "PartiallyFilledCanceled" else "New")
        .put("price", "60000").put("qty", "0.01").put("filled_qty", if (history) "0.005" else "0")
        .put("filled_value", if (history) "310" else "0").put("avg_price", if (history) "62000" else JSONObject.NULL)
        .put("created_at", "2026-09-25T09:00:00Z").put("updated_at", AS_OF)))
        .put("next_cursor", JSONObject.NULL).put("freshness", freshness())

    private fun executions(): JSONObject = JSONObject().put("items", JSONArray().put(JSONObject()
        .put("exec_id", EXECUTION_ID).put("order_id", HISTORY_ORDER_ID).put("symbol", "BTCUSDT")
        .put("base_coin", "BTC").put("quote_coin", "USDT").put("side", "buy").put("price", "62000")
        .put("qty", "0.005").put("value", "310").put("fee", "0.000005").put("fee_coin", "BTC")
        .put("is_maker", false).put("exec_time", "2026-09-20T12:00:00Z")))
        .put("next_cursor", JSONObject.NULL).put("freshness", freshness())

    private fun cashflow(): JSONObject = JSONObject().put("items", JSONArray().put(JSONObject()
        .put("id", "deposit:synthetic").put("kind", "deposit").put("coin", "USDT").put("amount", "1500")
        .put("status", "completed").put("occurred_at", "2026-02-01T08:00:00Z").put("chain", "TRX")
        .put("usd_value_at_event", "1500")))
        .put("totals", JSONObject().put("by_coin", JSONArray().put(JSONObject().put("coin", "USDT")
            .put("deposited", "1500").put("withdrawn", "0").put("net", "1500")))
            .put("usd", JSONObject().put("deposited", "1500").put("withdrawn", "0").put("unpriced_count", 0)))
        .put("next_cursor", JSONObject.NULL).put("freshness", freshness())

    private fun flows(): JSONObject = JSONObject().put("items", JSONArray().put(JSONObject()
        .put("id", FLOW_ID).put("account", "funding").put("kind", "deposit").put("operation", "deposit")
        .put("direction", "in").put("coin", "USDT").put("amount", "1500").put("counterparty", "chain")
        .put("external", true).put("occurred_at", "2026-02-01T08:00:00Z").put("usd_value_at_event", "1500")
        .put("markable", true).put("treatment", flowTreatment).put("unit_cost_usd", flowUnitCost ?: JSONObject.NULL)))
        .put("totals", JSONObject()
            .put("by_coin", JSONArray().put(JSONObject().put("coin", "USDT").put("in", "1500").put("out", "500").put("net", "1000")))
            .put("by_kind", JSONArray().put(JSONObject().put("kind", "deposit").put("count", 1).put("usd_in", "1500").put("usd_out", "0")))
            .put("usd", JSONObject().put("in", "1500").put("out", "500").put("net", "1000").put("unpriced_count", 0))
            .put("by_month", JSONArray().put(JSONObject().put("month", "2026-08").put("usd_in", "1500").put("usd_out", "0"))
                .put(JSONObject().put("month", "2026-09").put("usd_in", "0").put("usd_out", "500"))))
        .put("next_cursor", JSONObject.NULL).put("freshness", freshness())

    private fun portfolio(): JSONObject = JSONObject().put("total_usd", totalUsd)
        .put("change", JSONObject().put("day", JSONObject().put("usd_delta", "20.5").put("pct", "0.82")
            .put("net_flow_usd", "0").put("market_usd", "20.5").put("market_pct", "0.82")))
        .put("by_account", JSONObject().put("unified", "1520.5").put("funding", "1000"))
        .put("balances", JSONArray().put(JSONObject().put("coin", "BTC").put("quantity", "0.02")
            .put("usd_value", "1260").put("price_usd", "63000").put("share_pct", "49.99")
            .put("accounts", JSONObject().put("unified", "0.02").put("funding", JSONObject.NULL).put("earn", JSONObject.NULL))
            .put("day", JSONObject().put("quantity_delta", "0").put("usd_delta", "20").put("pct", "1.61"))))
        .put("earn", JSONArray()).put("freshness", freshness())

    private fun history(): JSONObject = JSONObject().put("points", JSONArray()
        .put(JSONObject().put("time", "2026-09-23T10:00:00Z").put("total_usd", "2480").put("flow_usd", "0").put("rebuilt", false))
        .put(JSONObject().put("time", "2026-09-24T10:00:00Z").put("total_usd", "2500").put("flow_usd", "0").put("rebuilt", false))
        .put(JSONObject().put("time", AS_OF).put("total_usd", totalUsd).put("flow_usd", "0").put("rebuilt", false)))
        .put("earliest_available", "2026-09-01T10:00:00Z").put("freshness", freshness())

    private fun position(): JSONObject = JSONObject().put("coin", "BTC").put("open_qty", "0.02")
        .put("value", "1260").put("held_qty", "0.02").put("unexplained_qty", "0").put("traded_against", "USDT")
        .put("closed", false).put("result", "75").put("core", core?.let {
            JSONObject(it.toString()).put("asked", it.getString("qty")).put("entry", "60000")
                .put("unrealized", "30").put("sold_qty", "0").put("sold_price", JSONObject.NULL).put("sold_unrealized", "0")
        } ?: JSONObject.NULL).put("avg_entry", "60000")
        .put("break_even", "59900").put("cycle_started_at", "2026-09-01T10:00:00Z").put("realized_in_cycle", "15")
        .put("mark", "63000").put("unrealized", "60").put("unrealized_pct", "5")
        .put("realized", "15").put("realized_in_range", "15").put("income", "0").put("income_in_range", "0")
        .put("fees", "1.2").put("avg_buy", "60000").put("issues", JSONArray())

    private fun positions(): JSONObject = JSONObject().put("unit", strategyCoin ?: "USD").put("strategy_coin", strategyCoin ?: JSONObject.NULL)
        .put("lot_method", lotMethod).put("as_of", JSONObject.NULL).put("items", JSONArray().put(position()))
        .put("totals", JSONObject().put("realized", "15").put("realized_in_range", "15").put("unrealized", "60")
            .put("income", "0").put("income_in_range", "0").put("fees", "1.2").put("value", "1260").put("result", "75")
            .put("realized_by_month", JSONArray().put(JSONObject().put("month", "2026-09").put("realized", "15")))
            .put("issues", JSONArray())).put("freshness", freshness())

    private fun chart(): JSONObject = JSONObject().put("coin", "BTC").put("unit", "USD").put("lot_method", "lifo")
        .put("from", "2026-09-23T10:00:00Z").put("points", JSONArray()
            .put(JSONObject().put("time", "2026-09-23T10:00:00Z").put("price", "61000"))
            .put(JSONObject().put("time", "2026-09-24T10:00:00Z").put("price", "62000"))
            .put(JSONObject().put("time", AS_OF).put("price", "63000")))
        .put("trades", JSONArray().put(JSONObject().put("time", "2026-09-23T12:00:00Z").put("side", "buy")
            .put("qty", "0.02").put("fills", 1).put("price", "60000")))
        .put("lines", JSONObject().put("avg_entry", "60000").put("break_even", "59900")
            .put("core_entry", JSONObject.NULL).put("mark", "63000"))
        .put("freshness", freshness()).put("issues", JSONArray())

    private fun configuration(): JSONObject {
        val keyState = JSONObject().put("present", connected)
        if (connected) keyState.put("hint", "••••fixture")
        return JSONObject().put("contract", "assistant.configuration/v1")
            .put("status", if (connected) "configured" else "not_configured").put("revision", if (connected) revision else 0)
            .put("public", if (connected) JSONObject(publicSettings.toString()) else JSONObject().put("environment", "mainnet").put("history_days", 730))
            .put("secret_state", JSONObject().put("api_key", keyState).put("api_secret", JSONObject().put("present", connected)))
            .put("readiness", JSONObject().put("state", if (connected) "ready" else "action_required").put("capabilities", JSONObject())
                .put("issues", if (connected) JSONArray() else JSONArray().put(JSONObject().put("code", "not_configured").put("recovery", "fix_arguments"))))
            .put("environments", JSONArray(listOf("mainnet", "testnet"))).put("egress_ips", JSONArray(listOf("192.0.2.25")))
            .also { if (connected) it.put("connection_state", "active").put("key_info", JSONObject().put("read_only", true).put("ip_bound", true)) }
    }

    private fun freshness(): JSONObject = JSONObject().put("as_of", AS_OF).put("connection_state", "active")
        .put("stream", "live").put("last_reconcile_at", "2026-09-25T09:58:00Z")
        .put("history_complete", true).put("coverage_from", "2026-09-01T10:00:00Z")

    private fun result(data: JSONObject): JSONObject = JSONObject().put("contract", "assistant.result/v1")
        .put("operation_key", "synthetic-read").put("state", "completed").put("data", data)
        .put("receipts", JSONArray()).put("effects", JSONArray())

    private fun json(body: JSONObject, status: Int = 200): Reply = Reply(status, body.toString())
    data class Reply(val status: Int, val body: String)

    companion object {
        const val INSTALLATION_ID = "synthetic-trading"
        const val BASE = "/v1/plugins/installations/$INSTALLATION_ID"
        const val AS_OF = "2026-09-25T10:00:00Z"
        const val SYNTHETIC_KEY = "SyntheticKey25aOnly"
        const val SYNTHETIC_SECRET = "SyntheticSecret25aNeverValid"
        const val FLOW_ID = "funding:c3ludGhldGlj"
        const val OPEN_ORDER_ID = "synthetic-open-order"
        const val HISTORY_ORDER_ID = "synthetic-history-order"
        const val EXECUTION_ID = "synthetic-execution"
        private val WRITE_TOOLS = listOf("positions.strategy", "positions.method", "positions.core", "flows.mark", "sync.refill")
        private val READ_TOOLS = listOf("portfolio.get", "portfolio.history", "position.summary", "position.chart",
            "positions.list", "orders.open", "orders.history", "executions.list", "flows.list", "sync.status", "symbols.list", "cashflow.list")
        private fun major(tool: String): Int = when (tool) {
            "position.summary" -> 5
            "positions.list" -> 4
            else -> 1
        }
    }
}
