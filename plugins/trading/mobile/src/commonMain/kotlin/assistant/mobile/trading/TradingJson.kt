package assistant.mobile.trading

import kotlinx.serialization.json.Json

/** Package contracts allow additive fields while required wire types remain checked. */
internal val TradingJson = Json { ignoreUnknownKeys = true }
