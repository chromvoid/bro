package assistant.mobile.android

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.design.ui.SegmentedChoice
import assistant.mobile.android.body.BodyFilamentView
import assistant.mobile.android.body.assets.AndroidBodyAssetRepository
import assistant.mobile.android.body.assets.BodyAssetFiles
import assistant.mobile.core.SessionLease
import assistant.mobile.core.body.*
import assistant.mobile.core.bodyassets.BodyAssetError
import assistant.mobile.core.bodyassets.BodyAssetFailure
import assistant.mobile.core.bodyassets.BodyAssetProgress
import assistant.mobile.core.personal.PersonalDisplayUnits
import assistant.mobile.core.personal.PersonalOverview
import assistant.mobile.core.personal.PersonalProfile
import assistant.mobile.core.personal.personalAmountText
import assistant.mobile.design.R as DesignR
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class BodyFigureStatus(
    val loading: Boolean = true,
    val poster: Boolean = false,
    val assetsError: Boolean = false,
)

private val BodyAtlasSaver = listSaver<BodyAtlasState, Any>(
    save = { listOf(it.layer.wire, it.view.wire, it.yaw ?: Double.NaN, it.pitch, it.distance,
        it.comparing, it.comparisonId.orEmpty(), it.comparisonFraction) },
    restore = { BodyAtlasState(
        layer = BodyLayer.entries.first { value -> value.wire == it[0] },
        view = BodyView.fromWire(it[1] as String),
        yaw = (it[2] as Double).takeIf { value -> value.isFinite() },
        pitch = it[3] as Double,
        distance = it[4] as Double,
        comparing = it[5] as Boolean,
        comparisonId = (it[6] as String).ifEmpty { null },
        comparisonFraction = it[7] as Double,
    ) },
)

@Composable
internal fun PersonalOverviewBodyAtlas(
    overview: PersonalOverview,
    day: String,
    canWrite: Boolean,
    timezone: String,
    locale: String,
    lease: SessionLease,
    presentation: BodyPresentationController,
    assets: AndroidBodyAssetRepository,
    onOpenProfile: () -> Unit,
) {
    BodyPresentationActivation(presentation, lease)
    val observed by presentation.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    LaunchedEffect(day, timezone, presentation) { presentation.history(day, timezone) }
    val history = state?.history?.data?.takeIf { state?.historyDay == day }
    val dashboard = remember(overview, history, day, timezone) { dashboardBody(overview, history, day, timezone) }
    dashboard.current?.let { analysis ->
        BodyAtlas(analysis, dashboard.analyses, overview.profile, canWrite, timezone, locale, lease,
            presentation, assets, onOpenProfile)
    }
}

/** Both entry points use the same common scan mapping, comparison and animation state. */
@Composable
internal fun BodyAtlas(
    analysis: BodyAnalysis,
    analyses: List<BodyAnalysis>,
    profile: PersonalProfile?,
    canWrite: Boolean,
    timezone: String,
    locale: String,
    lease: SessionLease,
    presentation: BodyPresentationController,
    assets: AndroidBodyAssetRepository,
    onOpenProfile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BodyPresentationActivation(presentation, lease)
    val observed by presentation.state.collectAsStateWithLifecycle()
    val preference = observed?.takeIf { it.namespace == lease.namespace }
    val context = LocalContext.current
    val localViews = remember(context) { context.getSharedPreferences("body-atlas-views", 0) }
    val viewKey = remember(lease.namespace) {
        MessageDigest.getInstance("SHA-256").digest(
            listOf(lease.namespace.instance, lease.namespace.origin, lease.namespace.userId).joinToString("\u0000").toByteArray(),
        ).joinToString("") { "%02x".format(it) }
    }
    val savedView = remember(viewKey) {
        BodyView.entries.firstOrNull { it.wire == localViews.getString(viewKey, null) } ?: BodyView.Front
    }
    var state by rememberSaveable(lease.namespace, stateSaver = BodyAtlasSaver) { mutableStateOf(BodyAtlasState(view = savedView)) }
    var previousAnalysisId by rememberSaveable(lease.namespace) { mutableStateOf(analysis.id) }
    LaunchedEffect(analysis.id) {
        if (previousAnalysisId != analysis.id) {
            state = state.changedAnalysis()
            previousAnalysisId = analysis.id
        }
    }
    LaunchedEffect(viewKey, savedView) { if (state.view != savedView) state = state.setView(savedView) }
    var figure by remember { mutableStateOf(BodyFigureStatus()) }
    val mapping = remember(analysis, profile) { bodyVisual(analysis, profile) }
    val candidates = remember(analyses, analysis, profile) { bodyComparisonScans(analyses, analysis, profile) }
    val previous = candidates.firstOrNull { it.id == state.comparisonId } ?: candidates.firstOrNull()
    val comparing = state.comparing && previous != null
    val previousMapping = remember(previous, profile) { previous?.let { bodyVisual(it, profile) } }
    val visual = if (comparing && previousMapping != null)
        interpolateBodyVisual(previousMapping.visual, mapping.visual, state.comparisonFraction) else mapping.visual
    val character = preference?.presentation?.character ?: BodyCharacter.Sculpture
    val scope = rememberCoroutineScope()
    val units = profile?.displayUnits ?: PersonalDisplayUnits.Metric
    val title = stringResource(DesignR.string.personal_atlas_title)
    OutlinedCard(modifier.fillMaxWidth().testTag("body_atlas").semantics { contentDescription = title }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(DesignR.string.personal_atlas_eyebrow), style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("${analysis.sourceName ?: stringResource(DesignR.string.personal_unnamed_source)} · ${personalMoment(analysis.measuredAt, timezone, locale)}",
                style = MaterialTheme.typography.bodySmall)
            if (profile != null && (mapping.genderMissing || mapping.heightMissing)) {
                if (mapping.genderMissing) Text(stringResource(DesignR.string.personal_atlas_gender_missing), style = MaterialTheme.typography.bodySmall)
                if (mapping.heightMissing) Text(stringResource(DesignR.string.personal_atlas_height_missing), style = MaterialTheme.typography.bodySmall)
                if (canWrite) TextButton(onClick = onOpenProfile, modifier = Modifier.heightIn(min = 48.dp).testTag("atlas_open_profile")) {
                    Text(stringResource(if (mapping.genderMissing) DesignR.string.personal_atlas_specify_gender else DesignR.string.personal_atlas_specify_height))
                }
            }
            Text(stringResource(DesignR.string.personal_atlas_character_title), style = MaterialTheme.typography.labelLarge)
            val characterLabels = BodyCharacter.entries.associateWith { stringResource(if (it == BodyCharacter.Sculpture)
                DesignR.string.personal_atlas_character_sculpture else DesignR.string.personal_atlas_character_spartan) }
            SegmentedChoice(
                options = BodyCharacter.entries,
                selected = character,
                onSelect = { choice -> scope.launch { presentation.select(choice) } },
                label = { characterLabels.getValue(it) },
                enabled = canWrite && preference?.writable == true,
                optionEnabled = { !(it == BodyCharacter.Spartan && visual.template == BodyTemplate.Female) },
                optionTestTag = { "atlas_character_${it.wire}" },
            )
            if (visual.template == BodyTemplate.Female) Text(stringResource(if (character == BodyCharacter.Spartan)
                DesignR.string.personal_atlas_character_saved_incompatible else DesignR.string.personal_atlas_character_male_only),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("atlas_character_unavailable"))
            if (preference?.error != null) {
                ErrorMessage(requireNotNull(preference.error), "atlas_preference_error")
                TextButton(onClick = { scope.launch { presentation.refresh() } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(DesignR.string.common_retry))
                }
            }
            if (preference?.busy == true) Text(stringResource(DesignR.string.personal_atlas_character_saving),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            val layerLabels = BodyLayer.entries.associateWith { atlasLayerName(it) }
            SegmentedChoice(
                options = BodyLayer.entries,
                selected = state.layer,
                onSelect = { state = state.copy(layer = it) },
                label = { layerLabels.getValue(it) },
                optionTestTag = { "atlas_layer_${it.wire}" },
            )
            NativeBodyFigure(visual, character, state, comparing, assets, onYaw = { state = state.rotate(it) },
                onStatus = { figure = it }, modifier = Modifier.fillMaxWidth().height(380.dp))
            state.layer.kinds.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { kind ->
                        val record = analysis.values[kind]
                        Column(Modifier.weight(1f).testTag("atlas_$kind"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(personalMetricLabel(kind), style = MaterialTheme.typography.labelMedium)
                            Text(personalAmountText(kind, record?.value, units, locale) ?: "—", style = MaterialTheme.typography.titleLarge)
                            if (record == null) Text(stringResource(if (analysis.records.count { it.kind == kind && !it.excluded } > 1)
                                DesignR.string.personal_disagree else DesignR.string.personal_no_value), style = MaterialTheme.typography.bodySmall)
                            if (comparing && previous != null) {
                                Text(stringResource(DesignR.string.personal_atlas_was_value,
                                    personalAmountText(kind, previous.values[kind]?.value, units, locale) ?: "—"), style = MaterialTheme.typography.bodySmall)
                                if (record != null) previous.values[kind]?.let { before -> bodyDelta(record, before, units, locale)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                } }
                            } else if (record != null) analysis.previous[kind]?.let { before ->
                                bodyDelta(record, before.record, units, locale)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    Text(stringResource(DesignR.string.personal_compared_with, personalMoment(before.measuredAt, timezone, locale)), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (record?.corrected == true) Text(stringResource(DesignR.string.personal_origin_corrected), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(stringResource(DesignR.string.personal_atlas_view_title), style = MaterialTheme.typography.labelLarge)
            val viewLabels = BodyView.entries.associateWith { atlasViewName(it) }
            SegmentedChoice(
                options = BodyView.entries,
                selected = state.view.takeIf { state.yaw == null },
                onSelect = { view ->
                    state = state.setView(view)
                    localViews.edit().putString(viewKey, view.wire).apply()
                },
                label = { viewLabels.getValue(it) },
                enabled = !figure.loading && !figure.poster && !figure.assetsError,
                optionTestTag = { "atlas_view_${it.wire}" },
            )
            HorizontalDivider()
            OutlinedButton(onClick = { state = state.compare(!comparing) }, enabled = candidates.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("atlas_compare").semantics { selected = comparing }) {
                Text(stringResource(if (comparing) DesignR.string.personal_atlas_close_comparison else DesignR.string.personal_atlas_compare))
            }
            if (candidates.isEmpty()) Text(stringResource(DesignR.string.personal_atlas_compare_unavailable), style = MaterialTheme.typography.bodySmall)
            if (comparing && previous != null) {
                PersonalHistoryChoice(previous.id, candidates.map { it.id to (personalMoment(it.measuredAt, timezone, locale) + " · " +
                    stringResource(DesignR.string.personal_atlas_scan_number, (analyses.size - analyses.indexOf(it)).toString())) },
                    stringResource(DesignR.string.personal_atlas_compare_from), "atlas_compare_analysis") { state = state.previous(it) }
                Text("${stringResource(DesignR.string.personal_atlas_before)} · ${personalMoment(previous.measuredAt, timezone, locale)}",
                    style = MaterialTheme.typography.bodySmall)
                Text("${stringResource(DesignR.string.personal_atlas_selected)} · ${personalMoment(analysis.measuredAt, timezone, locale)}",
                    style = MaterialTheme.typography.bodySmall)
                val scrubDescription = stringResource(DesignR.string.personal_atlas_scrub)
                val scrubPosition = stringResource(DesignR.string.personal_atlas_scrub_position, (state.comparisonFraction * 100).toInt().toString())
                Slider(value = state.comparisonFraction.toFloat(), onValueChange = { state = state.scrub(it.toDouble()) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("atlas_compare_progress").semantics {
                        contentDescription = scrubDescription
                        stateDescription = scrubPosition
                    })
                val held = listOfNotNull(
                    stringResource(DesignR.string.personal_atlas_axis_fat).takeIf { visual.fat == null || previousMapping?.visual?.fat == null },
                    stringResource(DesignR.string.personal_atlas_axis_muscle).takeIf { visual.muscle == null || previousMapping?.visual?.muscle == null },
                )
                if (held.isNotEmpty()) Text(stringResource(DesignR.string.personal_atlas_held_axes, held.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text(stringResource(when (state.layer) {
                BodyLayer.Composition -> DesignR.string.personal_atlas_note_composition
                BodyLayer.Muscle -> DesignR.string.personal_atlas_note_muscle
                BodyLayer.Fat -> DesignR.string.personal_atlas_note_fat
                BodyLayer.Water -> DesignR.string.personal_atlas_note_water
            }), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(DesignR.string.personal_atlas_schematic), style = MaterialTheme.typography.bodySmall)
            if (!figure.loading && !figure.poster && !figure.assetsError) AtlasCalibrationHint(mapping, previousMapping.takeIf { comparing })
            if (figure.poster && !figure.loading) Text(stringResource(DesignR.string.personal_atlas_static_view), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AtlasCalibrationHint(mapping: BodyVisual, previous: BodyVisual?) {
    val selected = stringResource(DesignR.string.personal_atlas_selected)
    val before = stringResource(DesignR.string.personal_atlas_before)
    val endpoints = if (previous != null) listOf(before to previous, selected to mapping) else listOf("" to mapping)
    val boundaries = endpoints.mapNotNull { (label, value) ->
        val axes = BodyAxis.entries.mapNotNull { axis -> value.calibrationBoundaries[axis]?.let { direction ->
            stringResource(DesignR.string.personal_atlas_calibration_axis_boundary,
                stringResource(if (axis == BodyAxis.Fat) DesignR.string.personal_atlas_calibration_axis_fat else DesignR.string.personal_atlas_calibration_axis_muscle),
                stringResource(if (direction == CalibrationBoundary.Below) DesignR.string.personal_atlas_calibration_direction_below else DesignR.string.personal_atlas_calibration_direction_above))
        } }
        axes.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { if (label.isEmpty()) it else "$label: $it" }
    }
    if (boundaries.isNotEmpty()) Text(stringResource(if (previous == null) DesignR.string.personal_atlas_calibration_hint
        else DesignR.string.personal_atlas_calibration_comparison_hint, boundaries.joinToString(" · ")),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("atlas_calibration_hint").semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable private fun atlasLayerName(layer: BodyLayer): String = stringResource(when (layer) {
    BodyLayer.Composition -> DesignR.string.personal_atlas_layer_composition
    BodyLayer.Muscle -> DesignR.string.personal_atlas_layer_muscle
    BodyLayer.Fat -> DesignR.string.personal_atlas_layer_fat
    BodyLayer.Water -> DesignR.string.personal_atlas_layer_water
})
@Composable private fun atlasViewName(view: BodyView): String = stringResource(when (view) {
    BodyView.Front -> DesignR.string.personal_atlas_view_front
    BodyView.Side -> DesignR.string.personal_atlas_view_side
    BodyView.Back -> DesignR.string.personal_atlas_view_back
    BodyView.ThreeQuarter -> DesignR.string.personal_atlas_view_three_quarter
})
@Composable
private fun BodyPresentationActivation(controller: BodyPresentationController, lease: SessionLease) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(controller, lease, owner) {
        val token = Any()
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> controller.activate(owner = token)
            Lifecycle.Event.ON_STOP -> controller.deactivate(token)
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate(owner = token)
        onDispose { owner.lifecycle.removeObserver(observer); controller.deactivate(token) }
    }
}

/** Network and integrity failures keep their honest states; only a GPU failure selects a poster. */
@Composable
private fun NativeBodyFigure(
    visual: BodyVisualState,
    character: BodyCharacter,
    state: BodyAtlasState,
    comparing: Boolean,
    assets: AndroidBodyAssetRepository,
    onYaw: (Double) -> Unit,
    onStatus: (BodyFigureStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var files by remember(assets) { mutableStateOf<BodyAssetFiles?>(null) }
    var progress by remember(assets) { mutableStateOf<BodyAssetProgress?>(null) }
    var error by remember(assets) { mutableStateOf<BodyAssetError?>(null) }
    var retry by remember(assets) { mutableStateOf(0) }
    var reduced by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    var started by remember(lifecycle) { mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var visible by remember { mutableStateOf(true) }
    val variant = if (visual.template == BodyTemplate.Female) "female" else if (character == BodyCharacter.Spartan) "male_spartan" else "male"
    var rendererFailed by remember(variant, files) { mutableStateOf(false) }
    var figureReady by remember(variant, files) { mutableStateOf(false) }
    val clock = remember(variant, files) { BodyAnimationClock() }
    val frame = BodyRendererFrame(visual = visual, layer = state.layer, view = state.view, yaw = state.yaw ?: state.view.yaw,
        pitch = state.pitch, distance = state.distance,
        animate = started && visible, comparison = comparing, reducedMotion = reduced)
    val currentFrame by rememberUpdatedState(frame)
    val currentYaw by rememberUpdatedState(onYaw)
    val status = BodyFigureStatus(error == null && (files == null || (!figureReady && !rendererFailed)),
        rendererFailed, assetsError = error != null)
    LaunchedEffect(status) { onStatus(status) }
    LaunchedEffect(assets, retry) {
        error = null
        try { files = if (retry == 0) assets.load { progress = it } else assets.refresh { progress = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failed: BodyAssetFailure) { error = failed.error }
        catch (_: Exception) { error = BodyAssetError.Storage }
    }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = !ValueAnimator.areAnimatorsEnabled() }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!started) clock.reset()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); clock.reset() }
    }
    Box(modifier.testTag("body_figure").onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.bottom > 0f && bounds.top < context.resources.displayMetrics.heightPixels && bounds.width > 0f
    }) {
        val assetFiles = files
        if (assetFiles != null && !rendererFailed) key(variant, assetFiles) {
            val renderer = remember(context, variant, assetFiles) {
                BodyFilamentView(context).apply {
                    onReady = { figureReady = true }
                    onFailure = { failure ->
                        android.util.Log.e("BodyAtlas", "Filament renderer failed", failure)
                        rendererFailed = true
                    }
                    this.onYaw = { currentYaw(it) }
                    onFrameTime = { nanos ->
                        val animated = clock.advance(nanos, currentFrame)
                        setFrame(animated)
                    }
                }
            }
            DisposableEffect(renderer, lifecycle) {
                val observer = LifecycleEventObserver { _, event -> when (event) {
                    Lifecycle.Event.ON_STOP -> { renderer.setActive(false); clock.reset() }
                    Lifecycle.Event.ON_START -> renderer.setActive(visible)
                    else -> Unit
                } }
                lifecycle.lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.lifecycle.removeObserver(observer)
                    renderer.close()
                    clock.reset()
                }
            }
            LaunchedEffect(renderer) { renderer.loadFiles(assetFiles.require("$variant.glb"), assetFiles.require("$variant-lookaround.glb")) }
            LaunchedEffect(renderer, started, visible, reduced, comparing) {
                if (!started || !visible || reduced || comparing) clock.reset()
                renderer.setActive(started && visible)
            }
            AndroidView(factory = { renderer }, modifier = Modifier.fillMaxWidth().height(380.dp).testTag("body_figure_gpu"), update = {
                it.setFrame(currentFrame)
            })
        }
        if (rendererFailed && assetFiles != null) {
            val poster = remember(assetFiles, variant) { BitmapFactory.decodeFile(assetFiles.require("$variant.webp").absolutePath)?.asImageBitmap() }
            if (poster != null) Image(poster, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(380.dp).testTag("body_figure_poster"))
        }
        if (error != null) Column(Modifier.padding(16.dp).testTag("body_assets_error"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (error) {
                BodyAssetError.Network -> DesignR.string.errors_network
                BodyAssetError.Unauthorized -> DesignR.string.errors_session
                BodyAssetError.Forbidden -> DesignR.string.errors_forbidden
                BodyAssetError.Integrity -> DesignR.string.app_update_errors_integrity
                else -> DesignR.string.errors_unknown
            }), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp).testTag("body_assets_retry")) { Text(stringResource(DesignR.string.common_retry)) }
        } else if (files == null || (!figureReady && !rendererFailed)) Column(Modifier.fillMaxWidth().padding(16.dp).testTag("body_assets_loading"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(DesignR.string.personal_atlas_motion_loading), style = MaterialTheme.typography.bodySmall)
            progress?.let { current ->
                ProgressBar(progress = { current.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("${(current.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            } ?: ProgressBar(modifier = Modifier.fillMaxWidth())
        }
    }
}
