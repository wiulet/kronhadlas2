package com.btcsignal.app.engine

import android.content.Context
import com.btcsignal.app.data.model.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads strategies_parameters.json (bundled verbatim in assets/) into typed
 * [StrategyDef] objects. This is the ONE place strategy data is parsed. Every other
 * part of the app (Core Signal Engine, Strategy Dashboard, Strategy Comparison,
 * Backtest Engine) reads from the [StrategyDatabase] instance produced here — never
 * from a re-implemented copy. See spec sections 11/12/13.
 *
 * No thresholds, parameters, or conditions are altered or defaulted here: every
 * component field from the source JSON is preserved in [StrategyComponent.params].
 */
object StrategyRegistry {

    @Volatile
    private var cached: StrategyDatabase? = null

    fun load(context: Context): StrategyDatabase {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val text = context.assets.open("strategies_parameters.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val db = parse(text)
            cached = db
            return db
        }
    }

    fun parse(jsonText: String): StrategyDatabase {
        val root = JSONObject(jsonText)
        val meta = root.getJSONObject("meta")

        val hc = meta.getJSONObject("hard_constraints")
        val greenArr = hc.getJSONArray("entry_range_green_pct")
        val redArr = hc.getJSONArray("entry_range_red_pct")
        val outcomeRule = hc.optString("outcome_rule", "")
        // Bug Report 1.2: prefer the explicit boolean field. The old fallback only ever
        // checked whether the words "tie" and "Red" both appeared somewhere in the free-text
        // outcome_rule, which cannot detect negation ("tie does NOT count as Red" would still
        // match). It is kept ONLY for backward compatibility with a Strategy Database that
        // predates this field, and is never reached while outcome_tie_counts_as_red is present.
        val outcomeTieCountsAsRed = if (hc.has("outcome_tie_counts_as_red")) {
            hc.getBoolean("outcome_tie_counts_as_red")
        } else {
            outcomeRule.contains("tie", ignoreCase = true) && outcomeRule.contains("Red", ignoreCase = true)
        }
        val hardConstraints = HardConstraints(
            entryRangeGreenPct = greenArr.getDouble(0)..greenArr.getDouble(1),
            entryRangeRedPct = redArr.getDouble(0)..redArr.getDouble(1),
            outcomeTieCountsAsRed = outcomeTieCountsAsRed
        )

        val fm = meta.getJSONObject("financial_model")
        val financialModel = FinancialModelSpec(
            startCapitalUsd = fm.getDouble("start_capital_usd"),
            stakePerSignalUsd = fm.getDouble("stake_per_signal_usd"),
            winUsd = fm.getDouble("win_usd"),
            lossUsd = fm.getDouble("loss_usd"),
            breakevenWinRatePct = fm.getDouble("breakeven_win_rate_pct")
        )

        val strategiesJson: JSONArray = root.getJSONArray("strategies")
        val strategies = ArrayList<StrategyDef>(strategiesJson.length())
        for (i in 0 until strategiesJson.length()) {
            strategies.add(parseStrategy(strategiesJson.getJSONObject(i)))
        }

        return StrategyDatabase(
            asset = meta.optString("asset", "BTCUSDT"),
            targetTimeframe = meta.optString("target_timeframe", "5m"),
            hardConstraints = hardConstraints,
            financialModel = financialModel,
            strategies = strategies
        )
    }

    private fun parseStrategy(o: JSONObject): StrategyDef {
        val regimeGate = o.getJSONObject("regime_gate")
        val componentsJson = o.getJSONArray("components")
        val components = ArrayList<StrategyComponent>(componentsJson.length())
        for (i in 0 until componentsJson.length()) {
            components.add(parseComponent(componentsJson.getJSONObject(i)))
        }

        val perfJson = o.getJSONObject("performance")
        val isJson = perfJson.getJSONObject("in_sample")
        val oosJson = perfJson.getJSONObject("out_of_sample")
        val performance = StrategyPerformance(
            isSignals = isJson.getInt("n_signals"),
            isWinRatePct = isJson.getDouble("win_rate_pct"),
            oosSignals = oosJson.getInt("n_signals"),
            oosWinRatePct = oosJson.getDouble("win_rate_pct"),
            oosPnlPerSignalUsd = if (oosJson.has("pnl_per_signal_usd")) oosJson.getDouble("pnl_per_signal_usd") else null,
            oosZScoreVsBreakeven = if (oosJson.has("z_score_vs_breakeven")) oosJson.getDouble("z_score_vs_breakeven") else null
        )

        val wfJson = o.getJSONArray("walk_forward_blocks")
        val wfBlocks = ArrayList<WalkForwardBlock>(wfJson.length())
        for (i in 0 until wfJson.length()) {
            val b = wfJson.getJSONObject(i)
            wfBlocks.add(
                WalkForwardBlock(
                    block = b.getString("block"),
                    isOos = b.getBoolean("is_oos"),
                    n = b.getInt("n"),
                    wins = b.getInt("wins"),
                    losses = b.getInt("losses"),
                    winRate = if (b.isNull("win_rate")) null else b.getDouble("win_rate"),
                    pnl = b.getDouble("pnl")
                )
            )
        }

        return StrategyDef(
            id = o.getString("id"),
            marketConditionBucket = o.optString("market_condition_bucket", ""),
            regimeGateCode = regimeGate.getString("code"),
            regimeGateDescription = regimeGate.optString("description", ""),
            logicDescription = o.optString("logic", ""),
            components = components,
            directionDescription = o.optString("direction", ""),
            performance = performance,
            walkForwardBlocks = wfBlocks,
            maxSignalOverlapPct = o.optDouble("max_signal_overlap_with_other_selected_strategies_pct", 0.0),
            confidenceFlag = if (o.has("confidence_flag")) o.getString("confidence_flag") else null
        )
    }

    private fun parseComponent(o: JSONObject): StrategyComponent {
        val indicator = o.getString("indicator")
        val primitiveId = o.optString("primitive_id", null)
        val hypothesis = if (o.has("hypothesis")) o.getString("hypothesis") else null
        val params = LinkedHashMap<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "indicator" || k == "primitive_id" || k == "hypothesis") continue
            params[k] = jsonValueToKotlin(o.get(k))
        }
        return StrategyComponent(indicator, primitiveId, hypothesis, params)
    }

    private fun jsonValueToKotlin(value: Any): Any? = when (value) {
        is JSONObject -> value // left as-is; no component currently nests an object beyond top level
        is JSONArray -> (0 until value.length()).map { jsonValueToKotlin(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }
}
