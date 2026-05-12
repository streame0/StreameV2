package com.streame.tv.ui.screens.settings

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streame.tv.data.api.TraktDeviceCode
import com.streame.tv.data.model.Addon
import com.streame.tv.data.model.CatalogConfig
import com.streame.tv.data.model.CatalogDiscoveryResult
import com.streame.tv.data.model.CatalogKind
import com.streame.tv.data.model.CloudstreamPluginIndexEntry
import com.streame.tv.data.model.CloudstreamRepositoryManifest
import com.streame.tv.data.model.Profile
import com.streame.tv.data.model.QualityFilterConfig
import com.streame.tv.data.repository.AuthRepository
import com.streame.tv.data.repository.AuthState
import com.streame.tv.data.repository.CatalogDiscoveryRepository
import com.streame.tv.data.repository.CatalogRepository
import com.streame.tv.data.repository.CollectionTemplateManifest
import com.streame.tv.data.repository.LauncherContinueWatchingRepository
import com.streame.tv.data.repository.MediaRepository
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.repository.ProfileRepository
import com.streame.tv.data.repository.StreamRepository
import com.streame.tv.data.repository.CloudstreamRepositoryRecord
import com.streame.tv.data.repository.TraktRepository
import com.streame.tv.data.repository.TraktSyncService
import com.streame.tv.data.repository.WatchlistRepository
import com.streame.tv.network.OkHttpProvider
import com.streame.tv.data.repository.SyncProgress
import com.streame.tv.data.repository.SyncStatus
import com.streame.tv.data.repository.SyncResult
import com.streame.tv.ui.components.CARD_LAYOUT_MODE_LANDSCAPE
import com.streame.tv.ui.components.normalizeCardLayoutMode
import com.streame.tv.updater.ApkDownloader
import com.streame.tv.updater.ApkInstaller
import com.streame.tv.updater.AppUpdate
import com.streame.tv.updater.AppUpdateRepository
import com.streame.tv.updater.UpdatePreferences
import com.streame.tv.updater.VersionUtils
import com.streame.tv.util.LAST_APP_LANGUAGE_KEY
import com.streame.tv.util.settingsDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

enum class TorrServerStatus {
    UNKNOWN, TESTING, CONNECTED, UNREACHABLE, ERROR
}

enum class ToastType {
    SUCCESS, ERROR, INFO
}

data class SettingsUiState(
    val defaultSubtitle: String = "Off",
    val subtitleOptions: List<String> = emptyList(),
    val defaultAudioLanguage: String = "Auto (Original)",
    val audioLanguageOptions: List<String> = emptyList(),
    val cardLayoutMode: String = CARD_LAYOUT_MODE_LANDSCAPE,
    val frameRateMatchingMode: String = "Off",
    val autoPlayNext: Boolean = true,
    val autoPlaySingleSource: Boolean = true,
    val autoPlayMinQuality: String = "Any",
    val dnsProvider: String = "System DNS",
    val dnsProviderOptions: List<String> = listOf("System DNS", "Cloudflare", "Google", "AdGuard"),
    val subtitleSize: String = "Medium",
    val subtitleColor: String = "White",
    val filterSubtitlesByLanguage: Boolean = true,
    val secondarySubtitle: String = "Off",
    val trailerAutoPlay: Boolean = false,
    val showBudget: Boolean = true,
    // Volume boost in decibels (0 = off, up to 15 dB). Applied via system LoudnessEnhancer
    // attached to the ExoPlayer audio session. Issue #88.
    val volumeBoostDb: Int = 0,
    val showLoadingStats: Boolean = true,
    val includeSpecials: Boolean = false,
    // Trakt
    val isTraktAuthenticated: Boolean = false,
    val traktCode: TraktDeviceCode? = null,
    val isTraktAuthStarting: Boolean = false,
    val isTraktPolling: Boolean = false,
    val traktExpiration: String? = null,
    // Trakt Sync
    val isSyncing: Boolean = false,
    val syncProgress: SyncProgress = SyncProgress(),
    val lastSyncTime: String? = null,
    val syncedMovies: Int = 0,
    val syncedEpisodes: Int = 0,
    // App updates
    val isCheckingForUpdate: Boolean = false,
    val availableAppUpdate: AppUpdate? = null,
    val isAppUpdateAvailable: Boolean = false,
    val isDownloadingAppUpdate: Boolean = false,
    val appUpdateDownloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showAppUpdateDialog: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val appUpdateError: String? = null,
    // Catalogs
    val catalogs: List<CatalogConfig> = emptyList(),
    val catalogSearchQuery: String = "",
    val catalogSearchResults: List<CatalogDiscoveryResult> = emptyList(),
    val isCatalogSearching: Boolean = false,
    val catalogSearchError: String? = null,
    // Addons
    val addons: List<Addon> = emptyList(),
    val cloudstreamRepositories: List<CloudstreamRepositoryRecord> = emptyList(),
    val pendingCloudstreamManifest: CloudstreamRepositoryManifest? = null,
    val pendingCloudstreamRepoUrl: String? = null,
    val pendingCloudstreamPlugins: List<CloudstreamPluginIndexEntry> = emptyList(),
    val cloudstreamEnabled: Boolean = true,
    val cloudstreamSupportedApiVersion: Int = StreamRepository.SUPPORTED_CLOUDSTREAM_API_VERSION,
    val torrServerBaseUrl: String = "",
    val torrServerStatus: TorrServerStatus = TorrServerStatus.UNKNOWN,
    // Content language (TMDB metadata)
    val contentLanguage: String = "en-US",
    // Device mode override
    val deviceModeOverride: String = "auto",
    // Theme variant
    val themeVariant: String = "arctic",
    // Experience mode: "essential" hides advanced settings, "advanced" shows all
    val experienceMode: String = "advanced",
    // Home layout mode: "rows", "compact_grid", "dense_grid"
    val homeLayoutMode: String = "rows",
    // Poster shape: "landscape", "portrait", "square"
    val posterShape: String = "landscape",
    // Skip profile selection
    val skipProfileSelection: Boolean = false,
    val clockFormat: String = "24h",
    val qualityFilters: List<QualityFilterConfig> = emptyList(),
    val qualityFilterPresetLabel: String = "OFF",
    // Toast
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.INFO,
    // Supabase
    val isSupabaseAuthenticated: Boolean = false,
    val supabaseEmail: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
    private val traktRepository: TraktRepository,
    private val streamRepository: StreamRepository,
    private val mediaRepository: MediaRepository,
    private val catalogRepository: CatalogRepository,
    private val catalogDiscoveryRepository: CatalogDiscoveryRepository,
    private val watchlistRepository: WatchlistRepository,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val traktSyncService: TraktSyncService,
    private val launcherContinueWatchingRepository: LauncherContinueWatchingRepository,
    private val appUpdateRepository: AppUpdateRepository,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader,
    private val authManager: com.streame.tv.data.repository.AuthManager,
    private val profileSettingsSyncService: com.streame.tv.data.sync.ProfileSettingsSyncService,
    private val startupSyncService: com.streame.tv.data.sync.StartupSyncService,
    private val invalidationBus: com.streame.tv.data.sync.CloudSyncInvalidationBus
) : ViewModel() {
    private fun visibleCatalogs(catalogs: List<CatalogConfig>): List<CatalogConfig> {
        return catalogs.filter { config ->
            when (config.kind) {
                CatalogKind.COLLECTION -> false
                CatalogKind.COLLECTION_RAIL -> CollectionTemplateManifest.isValidCollectionConfig(config)
                else -> true
            }
        }
    }


    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observeSupabaseAuth()
    }

    private fun observeSupabaseAuth() {
        viewModelScope.launch {
            authManager.authState.collect { state ->
                val isAuthenticated = state is com.streame.tv.data.repository.SupabaseAuthState.FullAccount
                val email = (state as? com.streame.tv.data.repository.SupabaseAuthState.FullAccount)?.email
                _uiState.update { it.copy(isSupabaseAuthenticated = isAuthenticated, supabaseEmail = email) }
            }
        }
    }

    fun disconnectSupabase() {
        viewModelScope.launch {
            // Clear the cloud link from the current profile before signing out
            val activeProfileId = profileManager.getProfileIdSync()
            if (activeProfileId != "default") {
                profileRepository.clearCloudLink(activeProfileId)
            }
            authManager.signOut()
        }
    }

    fun deleteSupabaseAccount() {
        viewModelScope.launch {
            val result = authManager.deleteAccount()
            if (result.isFailure) {
                _uiState.update { it.copy(toastMessage = result.exceptionOrNull()?.message ?: "Failed to delete account", toastType = ToastType.ERROR) }
            }
        }
    }

    fun forceSync() {
        viewModelScope.launch { startupSyncService.pullAllData() }
    }

    /**
     * Push current profile settings to Supabase if authenticated.
     * Called after any settings change — fire-and-forget.
     */
    private fun pushProfileSettingsToRemote() {
        // Mark the edit time immediately so cloud pulls don't revert this change
        profileSettingsSyncService.lastLocalEditMs = System.currentTimeMillis()
        viewModelScope.launch {
            if (!authManager.isAuthenticated) return@launch
            try {
                val prefs = context.settingsDataStore.data.first()
                val jsonMap = prefs.asMap().mapKeys { (key, _) -> key.name }
                val jsonStr = gson.toJson(jsonMap)
                val jsonElement = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr)
                if (jsonElement is kotlinx.serialization.json.JsonObject) {
                    val profileId = resolveProfileId()
                    profileSettingsSyncService.pushToRemote(profileId = profileId, settingsJson = jsonElement)
                }
            } catch (_: Exception) { }
            invalidationBus.markDirty(
                com.streame.tv.data.sync.CloudSyncScope.PROFILE_SETTINGS,
                reason = "settingsChanged"
            )
        }
    }

    private suspend fun resolveProfileId(): Int {
        val activeId = profileManager.getProfileId()
        if (activeId == "default") return 1
        val profiles = profileManager.getProfileList()
        val index = profiles.indexOfFirst { it.id == activeId }
        return if (index >= 0) index + 1 else 1
    }

    private fun contentLanguageKey() = profileManager.profileStringKey("content_language")

    private fun defaultSubtitleKey() = profileManager.profileStringKey("default_subtitle")
    private fun defaultSubtitleKeyFor(profileId: String) = profileManager.profileStringKeyFor(profileId, "default_subtitle")
    private fun subtitleSettingsUpdatedAtKey() = profileManager.profileStringKey("subtitle_settings_updated_at")
    private fun defaultAudioLanguageKey() = profileManager.profileStringKey("default_audio_language")
    private fun defaultAudioLanguageKeyFor(profileId: String) = profileManager.profileStringKeyFor(profileId, "default_audio_language")
    private fun subtitleUsageKey() = profileManager.profileStringKey("subtitle_usage_v1")
    private fun cardLayoutModeKey() = profileManager.profileStringKey("card_layout_mode")
    private fun cardLayoutModeKeyFor(profileId: String) = profileManager.profileStringKeyFor(profileId, "card_layout_mode")
    private fun frameRateMatchingModeKey() = profileManager.profileStringKey("frame_rate_matching_mode")
    private fun frameRateMatchingModeKeyFor(profileId: String) = profileManager.profileStringKeyFor(profileId, "frame_rate_matching_mode")
    private fun autoPlayNextKey() = profileManager.profileBooleanKey("auto_play_next")
    private fun autoPlayNextKeyFor(profileId: String) = profileManager.profileBooleanKeyFor(profileId, "auto_play_next")
    private fun autoPlaySingleSourceKey() = profileManager.profileBooleanKey("auto_play_single_source")
    private fun autoPlaySingleSourceKeyFor(profileId: String) = profileManager.profileBooleanKeyFor(profileId, "auto_play_single_source")
    private fun autoPlayMinQualityKey() = profileManager.profileStringKey("auto_play_min_quality")
    private fun autoPlayMinQualityKeyFor(profileId: String) = profileManager.profileStringKeyFor(profileId, "auto_play_min_quality")
    private fun trailerAutoPlayKey() = profileManager.profileBooleanKey("trailer_auto_play")
    private fun showBudgetKey() = profileManager.profileBooleanKey("show_budget_on_home")
    private fun clockFormatKey() = profileManager.profileStringKey("clock_format")
    // Stored as a string because ProfileManager has no int helper and we only persist
    // a handful of discrete dB values. Parsed back to Int on read.
    private fun volumeBoostDbKey() = profileManager.profileStringKey("volume_boost_db")
    private fun showLoadingStatsKey() = profileManager.profileBooleanKey("show_loading_stats")

    private fun subtitleSizeKey() = profileManager.profileStringKey("subtitle_size")
    private fun subtitleColorKey() = profileManager.profileStringKey("subtitle_color")
    private fun filterSubtitlesByLanguageKey() = profileManager.profileBooleanKey("filter_subtitles_by_lang")
    private fun secondarySubtitleKey() = profileManager.profileStringKey("secondary_subtitle")
    private val dnsProviderKey = stringPreferencesKey(OkHttpProvider.DNS_PROVIDER_PREF_KEY)
    private fun includeSpecialsKey() = profileManager.profileBooleanKey("include_specials")
    private val qualityFiltersKey = stringPreferencesKey("quality_filters")
    private fun includeSpecialsKeyFor(profileId: String) = profileManager.profileBooleanKeyFor(profileId, "include_specials")
    private val gson = Gson()

    private var traktPollingJob: Job? = null
    private var catalogSearchJob: Job? = null
    private var observedProfileId: String? = null


    private enum class QualityFilterPreset(
        val label: String,
        val filterId: String?,
        val regexPattern: String?
    ) {
        OFF(label = "OFF", filterId = null, regexPattern = null),
        HD_1080_PLUS(
            label = "1080p+",
            filterId = "preset_quality_1080_plus",
            regexPattern = "(?:360|480|576|720)p|cam|hdcam|hdts|hdtc|telesync|telecine|ts|tc|screener|scr|sd"
        ),
        HD_1080_ONLY(
            label = "1080p only",
            filterId = "preset_quality_1080_only",
            regexPattern = "(?:2160|4k|uhd)|(?:360|480|576|720)p|cam|hdcam|hdts|hdtc|telesync|telecine|ts|tc|screener|scr|sd"
        ),
        HD_720_PLUS(
            label = "720p+",
            filterId = "preset_quality_720_plus",
            regexPattern = "(?:360|480|576)p|cam|hdcam|hdts|hdtc|telesync|telecine|ts|tc|screener|scr|sd"
        ),
        CUSTOM(label = "CUSTOM", filterId = null, regexPattern = null);

        fun toFilters(): List<QualityFilterConfig> {
            if (this == OFF || this == CUSTOM || filterId == null || regexPattern == null) return emptyList()
            return listOf(
                QualityFilterConfig(
                    id = filterId,
                    deviceName = "Preset: $label",
                    regexPattern = regexPattern,
                    enabled = true
                )
            )
        }
    }

    init {
        loadSettings()
        observeProfileChanges()
        observeAddons()
        observeCloudstreamRepositories()
        observeTorrServer()
        observeSyncState()
        initializeCatalogs()
        observeCatalogs()
        initializeUpdaterState()
        checkForAppUpdates(force = false, showNoUpdateFeedback = false)
    }

    private fun initializeUpdaterState() {
        // If the app was updated to a new version, clear any previously ignored tag
        // so future updates are shown again.
        viewModelScope.launch {
            val ignoredTag = updatePreferences.ignoredTag.first()
            if (ignoredTag != null) {
                val installedVersion = appUpdateRepository.getInstalledVersionName()
                val ignoredNormalized = com.streame.tv.updater.VersionUtils.normalize(ignoredTag)
                val installedNormalized = com.streame.tv.updater.VersionUtils.normalize(installedVersion)
                if (ignoredNormalized == installedNormalized || !com.streame.tv.updater.VersionUtils.isRemoteNewer(ignoredTag, installedVersion)) {
                    updatePreferences.setIgnoredTag(null)
                }
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            // Load local preferences first
            val prefs = context.settingsDataStore.data.first()
            var defaultSub = prefs[defaultSubtitleKey()] ?: "Off"
            val defaultAudio = prefs[defaultAudioLanguageKey()] ?: "Auto (Original)"
            val cardLayoutMode = normalizeCardLayoutMode(prefs[cardLayoutModeKey()])
            val frameRateMode = normalizeFrameRateMode(prefs[frameRateMatchingModeKey()])
            val deviceModeOverride = prefs[com.streame.tv.util.DEVICE_MODE_OVERRIDE_KEY] ?: "auto"
            val themeVariant = prefs[stringPreferencesKey("theme_variant")] ?: "arctic"
            val experienceMode = prefs[stringPreferencesKey("experience_mode")] ?: "advanced"
            val homeLayoutMode = prefs[stringPreferencesKey("home_layout_mode")] ?: "rows"
            val posterShape = prefs[stringPreferencesKey("poster_shape")] ?: "landscape"
            val skipProfileSelection = prefs[com.streame.tv.util.SKIP_PROFILE_SELECTION_KEY] ?: false
            val contentLang = prefs[contentLanguageKey()] ?: "en-US"
            // Apply content language to MediaRepository immediately
            mediaRepository.contentLanguage = if (contentLang == "en-US") null else contentLang
            var autoPlay = prefs[autoPlayNextKey()] ?: true
            var autoPlaySingleSource = prefs[autoPlaySingleSourceKey()] ?: true
            // Ensure defaults are persisted on first launch so they're never ambiguous
            if (prefs[autoPlaySingleSourceKey()] == null) {
                autoPlaySingleSource = true
                context.settingsDataStore.edit { it[autoPlaySingleSourceKey()] = true }
            }
            if (prefs[autoPlayNextKey()] == null) {
                context.settingsDataStore.edit { it[autoPlayNextKey()] = true }
            }
            val autoPlayMinQuality = normalizeAutoPlayMinQuality(prefs[autoPlayMinQualityKey()])
            val trailerAutoPlay = prefs[trailerAutoPlayKey()] ?: false
            val showBudget = prefs[showBudgetKey()] ?: true
            val clockFormat = prefs[clockFormatKey()] ?: "24h"
            val volumeBoostDb = prefs[volumeBoostDbKey()]?.toIntOrNull()?.coerceIn(0, 15) ?: 0
            val showLoadingStats = prefs[showLoadingStatsKey()] ?: true

            val subtitleSize = prefs[subtitleSizeKey()] ?: "Medium"
            val subtitleColor = prefs[subtitleColorKey()] ?: "White"
            val filterSubtitlesByLanguage = prefs[filterSubtitlesByLanguageKey()] ?: true
            val secondarySubtitle = prefs[secondarySubtitleKey()]?.trim()?.takeIf { it.isNotBlank() } ?: "Off"
            val dnsProviderValue = normalizeDnsProviderValue(prefs[dnsProviderKey])
            val includeSpecials = prefs[includeSpecialsKey()] ?: false
            val qualityFilters = runCatching {
                val json = prefs[qualityFiltersKey].orEmpty()
                if (json.isBlank()) {
                    emptyList()
                } else {
                    gson.fromJson<List<QualityFilterConfig>>(
                        json,
                        object : TypeToken<List<QualityFilterConfig>>() {}.type
                    ).orEmpty()
                }
            }.getOrDefault(emptyList())

            // Check auth statuses
            val isTrakt = traktRepository.isAuthenticated.first()

            // Get Trakt expiration if authenticated
            var traktExpiration: String? = null
            if (isTrakt) {
                traktExpiration = traktRepository.getTokenExpirationDate()
            }

            val subtitleOptions = loadSubtitleOptions(defaultSub)
            val audioLanguageOptions = loadAudioLanguageOptions(defaultAudio)
            val existingCatalogs = _uiState.value.catalogs.ifEmpty {
                mediaRepository.getDefaultCatalogConfigs()
            }

            val currentState = _uiState.value
            _uiState.value = currentState.copy(
                defaultSubtitle = defaultSub,
                subtitleOptions = subtitleOptions,
                defaultAudioLanguage = defaultAudio,
                audioLanguageOptions = audioLanguageOptions,
                cardLayoutMode = cardLayoutMode,
                frameRateMatchingMode = frameRateMode,
                autoPlayNext = autoPlay,
                autoPlaySingleSource = autoPlaySingleSource,
                autoPlayMinQuality = autoPlayMinQuality,
                trailerAutoPlay = trailerAutoPlay,
                showBudget = showBudget,
                volumeBoostDb = volumeBoostDb,
                showLoadingStats = showLoadingStats,

                subtitleSize = subtitleSize,
                subtitleColor = subtitleColor,
                filterSubtitlesByLanguage = filterSubtitlesByLanguage,
                secondarySubtitle = secondarySubtitle,
                dnsProvider = dnsProviderLabel(dnsProviderValue),
                includeSpecials = includeSpecials,
                isTraktAuthenticated = isTrakt,
                traktExpiration = traktExpiration,
                catalogs = existingCatalogs,
                contentLanguage = contentLang,
                deviceModeOverride = deviceModeOverride,
                themeVariant = themeVariant,
                experienceMode = experienceMode,
                homeLayoutMode = homeLayoutMode,
                posterShape = posterShape,
                skipProfileSelection = skipProfileSelection,
                clockFormat = clockFormat,
                qualityFilters = qualityFilters,
                qualityFilterPresetLabel = detectQualityFilterPreset(qualityFilters).label
            )
        }
    }

    private fun observeProfileChanges() {
        viewModelScope.launch {
            profileManager.activeProfileId.collect { profileId ->
                if (observedProfileId == profileId) return@collect
                observedProfileId = profileId
                loadSettings()
            }
        }
    }

    fun refreshSubtitleOptions() {
        viewModelScope.launch {
            val options = loadSubtitleOptions(_uiState.value.defaultSubtitle)
            if (_uiState.value.subtitleOptions != options) {
                _uiState.value = _uiState.value.copy(subtitleOptions = options)
            }
        }
    }

    fun refreshAudioLanguageOptions() {
        viewModelScope.launch {
            val options = loadAudioLanguageOptions(_uiState.value.defaultAudioLanguage)
            if (_uiState.value.audioLanguageOptions != options) {
                _uiState.value = _uiState.value.copy(audioLanguageOptions = options)
            }
        }
    }
    
    private fun observeAddons() {
        viewModelScope.launch {
            streamRepository.installedAddons.collect { addons ->
                runCatching {
                    catalogRepository.syncAddonCatalogs(addons)
                }
                if (_uiState.value.addons != addons) {
                    _uiState.value = _uiState.value.copy(addons = addons)
                }
            }
        }
    }

    private fun observeCloudstreamRepositories() {
        viewModelScope.launch {
            streamRepository.cloudstreamRepositories.collect { repositories ->
                if (_uiState.value.cloudstreamRepositories != repositories) {
                    _uiState.value = _uiState.value.copy(cloudstreamRepositories = repositories)
                }
            }
        }
    }

    private fun observeTorrServer() {
        viewModelScope.launch {
            streamRepository.observeTorrServerBaseUrl().collect { url ->
                if (_uiState.value.torrServerBaseUrl != url) {
                    _uiState.value = _uiState.value.copy(torrServerBaseUrl = url)
                }
            }
        }
    }

    private fun observeSyncState() {
        // Observe sync progress
        viewModelScope.launch {
            traktSyncService.syncProgress.collect { progress ->
                if (_uiState.value.syncProgress != progress) {
                    _uiState.value = _uiState.value.copy(syncProgress = progress)
                }
            }
        }

        // Observe sync status
        viewModelScope.launch {
            traktSyncService.isSyncing.collect { isSyncing ->
                if (_uiState.value.isSyncing != isSyncing) {
                    _uiState.value = _uiState.value.copy(isSyncing = isSyncing)
                }
            }
        }

        // Load last sync time
        viewModelScope.launch {
            val lastSync = traktSyncService.getLastSyncTime()
            _uiState.value = _uiState.value.copy(lastSyncTime = formatSyncTime(lastSync))
        }
    }

    private fun formatSyncTime(isoTime: String?): String? {
        if (isoTime == null) return null
        return try {
            val instant = java.time.Instant.parse(isoTime)
            val formatter = java.time.format.DateTimeFormatter
                .ofPattern("MMM dd, yyyy 'at' h:mm a")
                .withZone(java.time.ZoneId.systemDefault())
            formatter.format(instant)
        } catch (e: Exception) {
            null
        }
    }

    // ========== Trakt Sync ==========

    fun performFullSync(silent: Boolean = false) {
        viewModelScope.launch {
            if (_uiState.value.isSyncing) return@launch
            val result = traktSyncService.performFullSync()
            when (result) {
                is SyncResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        syncedMovies = result.moviesSynced,
                        syncedEpisodes = result.episodesSynced,
                        lastSyncTime = formatSyncTime(java.time.Instant.now().toString()),
                        toastMessage = "Synced ${result.moviesSynced} movies and ${result.episodesSynced} episodes",
                        toastType = ToastType.SUCCESS
                    )
                    // Invalidate repository cache to pick up new data
                    traktRepository.invalidateWatchedCache()
                    traktRepository.initializeWatchedCache()
                }
                is SyncResult.Error -> {
                    if (!silent) {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Sync failed: ${result.message}",
                            toastType = ToastType.ERROR
                        )
                    }
                }
            }
        }
    }

    fun performIncrementalSync() {
        viewModelScope.launch {
            val result = traktSyncService.performIncrementalSync()
            when (result) {
                is SyncResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        syncedMovies = _uiState.value.syncedMovies + result.moviesSynced,
                        syncedEpisodes = _uiState.value.syncedEpisodes + result.episodesSynced,
                        lastSyncTime = formatSyncTime(java.time.Instant.now().toString()),
                        toastMessage = if (result.moviesSynced == 0 && result.episodesSynced == 0)
                            "Already up to date"
                        else
                            "Synced ${result.moviesSynced} movies and ${result.episodesSynced} episodes",
                        toastType = ToastType.SUCCESS
                    )
                    // Invalidate repository cache to pick up new data
                    traktRepository.invalidateWatchedCache()
                    traktRepository.initializeWatchedCache()
                }
                is SyncResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        toastMessage = "Sync failed: ${result.message}",
                        toastType = ToastType.ERROR
                    )
                }
            }
        }
    }
    
    fun setDefaultSubtitle(language: String) {
        viewModelScope.launch {
            // Save locally
            val changedAt = System.currentTimeMillis()
            context.settingsDataStore.edit { prefs ->
                prefs[defaultSubtitleKey()] = language
                prefs[subtitleSettingsUpdatedAtKey()] = changedAt.toString()
            }
            pushProfileSettingsToRemote()
            _uiState.value = _uiState.value.copy(
                defaultSubtitle = language,
                subtitleOptions = loadSubtitleOptions(language)
            )

            // Sync to cloud
            authRepository.saveDefaultSubtitleToProfile(language)
        }
    }

    fun setDefaultAudioLanguage(language: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[defaultAudioLanguageKey()] = language
            }
            pushProfileSettingsToRemote()
            _uiState.value = _uiState.value.copy(
                defaultAudioLanguage = language,
                audioLanguageOptions = loadAudioLanguageOptions(language)
            )
        }
    }

    private suspend fun loadSubtitleOptions(current: String): List<String> {
        val prefs = context.settingsDataStore.data.first()
        val json = prefs[subtitleUsageKey()]
        val type = TypeToken.getParameterized(Map::class.java, String::class.java, Int::class.javaObjectType).type
        val usage: Map<String, Int> = if (!json.isNullOrBlank()) {
            gson.fromJson(json, type)
        } else {
            emptyMap()
        }

        val topUsed = usage.entries
            .sortedByDescending { it.value }
            .map { entry -> displayLanguage(entry.key) }
            .filter { it.isNotBlank() }
            .take(30)

        // Keep this list >= 25 items; this is the "always available" picker list.
        val defaults = listOf(
            "English",
            "Arabic",
            "Bengali",
            "Bulgarian",
            "Chinese",
            "Croatian",
            "Czech",
            "Danish",
            "Dutch",
            "Estonian",
            "Finnish",
            "French",
            "German",
            "Greek",
            "Hebrew",
            "Hindi",
            "Hungarian",
            "Indonesian",
            "Italian",
            "Japanese",
            "Korean",
            "Lithuanian",
            "Norwegian",
            "Persian",
            "Polish",
            "Portuguese",
            "Portuguese (Brazil)",
            "Romanian",
            "Russian",
            "Serbian",
            "Slovak",
            "Slovenian",
            "Spanish",
            "Swedish",
            "Thai",
            "Turkish",
            "Ukrainian",
            "Vietnamese"
        )
        val base = buildList {
            add("Off")
            add("Forced")
            if (current.isNotBlank() && current != "Off" && current != "Forced") add(current)
            addAll(topUsed)
            addAll(defaults)
        }

        return base.distinct().take(60)
    }

    private fun loadAudioLanguageOptions(current: String): List<String> {
        val defaults = listOf(
            "Auto (Original)",
            "English",
            "Arabic",
            "Bengali",
            "Bulgarian",
            "Chinese",
            "Croatian",
            "Czech",
            "Danish",
            "Dutch",
            "Estonian",
            "Finnish",
            "French",
            "German",
            "Greek",
            "Hebrew",
            "Hindi",
            "Hungarian",
            "Indonesian",
            "Italian",
            "Japanese",
            "Korean",
            "Lithuanian",
            "Norwegian",
            "Persian",
            "Polish",
            "Portuguese",
            "Portuguese (Brazil)",
            "Romanian",
            "Russian",
            "Serbian",
            "Slovak",
            "Slovenian",
            "Spanish",
            "Swedish",
            "Thai",
            "Turkish",
            "Ukrainian",
            "Vietnamese"
        )
        return buildList {
            if (current.isNotBlank()) add(current)
            addAll(defaults)
        }.distinct().take(60)
    }

    private fun displayLanguage(code: String): String {
        val normalized = code.trim()
        if (normalized.isBlank()) return ""
        val isCode = normalized.length <= 3 && normalized.all { it.isLetter() }
        if (!isCode) return normalized.replaceFirstChar { it.uppercase() }
        val locale = java.util.Locale(normalized)
        val name = locale.getDisplayLanguage(java.util.Locale.ENGLISH)
        return if (name.isNullOrBlank()) normalized else name
    }

    fun setAutoPlayNext(enabled: Boolean) {
        viewModelScope.launch {
            // Save locally
            context.settingsDataStore.edit { prefs ->
                prefs[autoPlayNextKey()] = enabled
            }
            pushProfileSettingsToRemote()
            _uiState.value = _uiState.value.copy(autoPlayNext = enabled)

            // Sync to cloud
            authRepository.saveAutoPlayNextToProfile(enabled)
        }
    }

    fun setAutoPlaySingleSource(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[autoPlaySingleSourceKey()] = enabled
            }
            _uiState.value = _uiState.value.copy(autoPlaySingleSource = enabled)
            pushProfileSettingsToRemote()
        }
    }

    fun setSecondarySubtitle(language: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[secondarySubtitleKey()] = language
            }
            _uiState.value = _uiState.value.copy(secondarySubtitle = language)
            pushProfileSettingsToRemote()
        }
    }

    fun setFilterSubtitlesByLanguage(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[filterSubtitlesByLanguageKey()] = enabled
            }
            _uiState.value = _uiState.value.copy(filterSubtitlesByLanguage = enabled)
            pushProfileSettingsToRemote()
        }
    }

    fun cycleAutoPlayMinQuality() {
        val current = normalizeAutoPlayMinQuality(_uiState.value.autoPlayMinQuality)
        val next = SettingsNormalizers.nextAutoPlayMinQuality(current)
        setAutoPlayMinQuality(next)
    }

    private fun setAutoPlayMinQuality(value: String) {
        val normalized = normalizeAutoPlayMinQuality(value)
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[autoPlayMinQualityKey()] = normalized
            }
            _uiState.value = _uiState.value.copy(autoPlayMinQuality = normalized)
            pushProfileSettingsToRemote()
        }
    }

    fun toggleCardLayoutMode() {
        val next = SettingsNormalizers.nextCardLayoutMode(_uiState.value.cardLayoutMode)
        setCardLayoutMode(next)
    }

    fun setCardLayoutMode(mode: String) {
        val normalized = normalizeCardLayoutMode(mode)
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[cardLayoutModeKey()] = normalized
            }
            _uiState.value = _uiState.value.copy(cardLayoutMode = normalized)
            pushProfileSettingsToRemote()
        }
    }

    /** Set content/metadata language for TMDB (e.g. "en-US", "fr-FR", "nl-NL"). */
    fun setContentLanguage(lang: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[contentLanguageKey()] = lang
                prefs[LAST_APP_LANGUAGE_KEY] = lang
            }
            pushProfileSettingsToRemote()
            // Mirror to SharedPreferences so attachBaseContext can read it synchronously on next launch
            context.getSharedPreferences("app_locale", android.content.Context.MODE_PRIVATE)
                .edit().putString("locale_tag", lang).apply()
            mediaRepository.contentLanguage = if (lang == "en-US") null else lang
            _uiState.value = _uiState.value.copy(contentLanguage = lang)
        }
    }

    /** Set UI mode override: "auto", "tv", "tablet", "phone". Requires app restart. */
    fun setDeviceModeOverride(mode: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[com.streame.tv.util.DEVICE_MODE_OVERRIDE_KEY] = mode
            }
            // Mirror to SharedPreferences so the next cold start's
            // pre-onCreate detectDeviceType() read picks it up synchronously.
            com.streame.tv.util.setDeviceModeOverrideCache(
                context,
                if (mode == "auto") null else mode,
            )
            _uiState.value = _uiState.value.copy(deviceModeOverride = mode)
            pushProfileSettingsToRemote()
        }
    }

    /** Set theme variant: "arctic", "oled", "dimmer". Applies immediately. */
    fun setThemeVariant(variant: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[stringPreferencesKey("theme_variant")] = variant
            }
            _uiState.value = _uiState.value.copy(themeVariant = variant)
            pushProfileSettingsToRemote()
        }
    }

    /** Set experience mode: "essential" hides advanced settings, "advanced" shows all. */
    fun setExperienceMode(mode: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[stringPreferencesKey("experience_mode")] = mode
            }
            _uiState.value = _uiState.value.copy(experienceMode = mode)
            pushProfileSettingsToRemote()
        }
    }

    /** Set home layout mode: "rows", "compact_grid", "dense_grid" */
    fun setHomeLayoutMode(mode: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[stringPreferencesKey("home_layout_mode")] = mode
            }
            _uiState.value = _uiState.value.copy(homeLayoutMode = mode)
            pushProfileSettingsToRemote()
        }
    }

    /** Set poster shape: "landscape", "portrait", "square" */
    fun setPosterShape(shape: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[stringPreferencesKey("poster_shape")] = shape
            }
            _uiState.value = _uiState.value.copy(posterShape = shape)
            pushProfileSettingsToRemote()
        }
    }

    fun setSkipProfileSelection(skip: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[com.streame.tv.util.SKIP_PROFILE_SELECTION_KEY] = skip
            }
            _uiState.value = _uiState.value.copy(skipProfileSelection = skip)
            pushProfileSettingsToRemote()
        }
    }

    fun cycleFrameRateMatchingMode() {
        val current = normalizeFrameRateMode(_uiState.value.frameRateMatchingMode)
        val next = SettingsNormalizers.nextFrameRateMode(current)
        setFrameRateMatchingMode(next)
    }

    fun setFrameRateMatchingMode(mode: String) {
        val normalized = normalizeFrameRateMode(mode)
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[frameRateMatchingModeKey()] = normalized
            }
            _uiState.value = _uiState.value.copy(frameRateMatchingMode = normalized)
            pushProfileSettingsToRemote()
        }
    }

    private fun normalizeFrameRateMode(raw: String?) = SettingsNormalizers.normalizeFrameRateMode(raw)
    private fun normalizeAutoPlayMinQuality(raw: String?) = SettingsNormalizers.normalizeAutoPlayMinQuality(raw)

    fun setTrailerAutoPlay(enabled: Boolean) {
        viewModelScope.launch { context.settingsDataStore.edit { it[trailerAutoPlayKey()] = enabled }; _uiState.value = _uiState.value.copy(trailerAutoPlay = enabled); pushProfileSettingsToRemote() }
    }

    fun setShowBudget(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[showBudgetKey()] = enabled }
            _uiState.value = _uiState.value.copy(showBudget = enabled)
            pushProfileSettingsToRemote()
        }
    }

    fun setShowLoadingStats(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[showLoadingStatsKey()] = enabled }
            _uiState.value = _uiState.value.copy(showLoadingStats = enabled)
            pushProfileSettingsToRemote()
        }
    }

    fun cycleClockFormat() {
        val next = SettingsNormalizers.nextClockFormat(_uiState.value.clockFormat)
        viewModelScope.launch {
            context.settingsDataStore.edit { it[clockFormatKey()] = next }
            _uiState.value = _uiState.value.copy(clockFormat = next)
            pushProfileSettingsToRemote()
        }
    }

    /**
     * Cycle the volume boost through discrete dB steps: 0 -> 3 -> 6 -> 9 -> 12 -> 15 -> 0.
     * 0 dB = LoudnessEnhancer disabled (no overhead, no clipping). Above +12 dB is
     * cropped to +15 dB since higher values tend to introduce audible distortion on
     * streaming content with already-compressed audio. Issue #88.
     */
    fun cycleVolumeBoost() {
        val current = _uiState.value.volumeBoostDb
        val next = SettingsNormalizers.nextVolumeBoost(current)
        viewModelScope.launch {
            context.settingsDataStore.edit { it[volumeBoostDbKey()] = next.toString() }
            _uiState.value = _uiState.value.copy(volumeBoostDb = next)
            pushProfileSettingsToRemote()
        }
    }

    fun cycleSubtitleSize() {
        val next = SettingsNormalizers.nextSubtitleSize(_uiState.value.subtitleSize)
        viewModelScope.launch { context.settingsDataStore.edit { it[subtitleSizeKey()] = next }; _uiState.value = _uiState.value.copy(subtitleSize = next); pushProfileSettingsToRemote() }
    }

    fun cycleSubtitleColor() {
        val next = SettingsNormalizers.nextSubtitleColor(_uiState.value.subtitleColor)
        viewModelScope.launch { context.settingsDataStore.edit { it[subtitleColorKey()] = next }; _uiState.value = _uiState.value.copy(subtitleColor = next); pushProfileSettingsToRemote() }
    }

    private fun normalizeDnsProviderValue(raw: String?) = SettingsNormalizers.normalizeDnsProviderValue(raw)
    private fun dnsProviderLabel(value: String) = SettingsNormalizers.dnsProviderLabel(value)
    private fun dnsProviderValueFromLabel(label: String) = SettingsNormalizers.dnsProviderValueFromLabel(label)

    fun setDnsProvider(label: String) {
        val value = dnsProviderValueFromLabel(label)
        viewModelScope.launch {
            val currentValue = dnsProviderValueFromLabel(_uiState.value.dnsProvider)
            if (value == currentValue) {
                return@launch
            }

            withContext(Dispatchers.IO) {
                OkHttpProvider.setDnsProvider(OkHttpProvider.parseDnsProvider(value))
                // Warm up the new DNS provider's lazy init off the main thread
                // so the first image request doesn't block
                runCatching { OkHttpProvider.dns.lookup("image.tmdb.org") }
            }
            context.settingsDataStore.edit { prefs ->
                prefs[dnsProviderKey] = value
            }
            _uiState.value = _uiState.value.copy(
                dnsProvider = dnsProviderLabel(value)
            )

            // Replace Coil image loader with one using the new DNS
            val imageLoader = withContext(Dispatchers.IO) {
                OkHttpProvider.createCoilImageLoader(context)
            }
            SingletonImageLoader.setUnsafe(imageLoader)
        }
    }

    fun setIncludeSpecials(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[includeSpecialsKey()] = enabled
            }
            _uiState.value = _uiState.value.copy(includeSpecials = enabled)
            pushProfileSettingsToRemote()
        }
    }

    fun addQualityFilter(deviceName: String, regexPattern: String) {
        val trimmedRegex = regexPattern.trim()
        if (trimmedRegex.isBlank()) return
        if (runCatching { Regex(trimmedRegex) }.isFailure) return

        viewModelScope.launch {
            val next = _uiState.value.qualityFilters + QualityFilterConfig(
                id = java.util.UUID.randomUUID().toString(),
                deviceName = deviceName.trim(),
                regexPattern = trimmedRegex,
                enabled = true
            )
            saveQualityFilters(next)
        }
    }

    fun updateQualityFilter(filterId: String, deviceName: String, regexPattern: String) {
        val trimmedRegex = regexPattern.trim()
        if (trimmedRegex.isBlank()) return
        if (runCatching { Regex(trimmedRegex) }.isFailure) return

        viewModelScope.launch {
            val next = _uiState.value.qualityFilters.map { filter ->
                if (filter.id == filterId) {
                    filter.copy(
                        deviceName = deviceName.trim(),
                        regexPattern = trimmedRegex
                    )
                } else {
                    filter
                }
            }
            saveQualityFilters(next)
        }
    }

    fun cycleQualityFilterPreset() {
        viewModelScope.launch {
            val currentPreset = detectQualityFilterPreset(_uiState.value.qualityFilters)
            
            // Prevent losing custom filters by cycling into a preset
            if (currentPreset == QualityFilterPreset.CUSTOM) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Custom filters detected — use manual editing to modify",
                    toastType = ToastType.INFO
                )
                return@launch
            }
            
            val nextPreset = when (currentPreset) {
                QualityFilterPreset.OFF -> QualityFilterPreset.HD_1080_PLUS
                QualityFilterPreset.HD_1080_PLUS -> QualityFilterPreset.HD_1080_ONLY
                QualityFilterPreset.HD_1080_ONLY -> QualityFilterPreset.HD_720_PLUS
                QualityFilterPreset.HD_720_PLUS -> QualityFilterPreset.OFF
                QualityFilterPreset.CUSTOM -> return@launch // Already handled above
            }
            saveQualityFilters(nextPreset.toFilters())
        }
    }

    fun toggleQualityFilter(filterId: String) {
        viewModelScope.launch {
            val next = _uiState.value.qualityFilters.map { filter ->
                if (filter.id == filterId) filter.copy(enabled = !filter.enabled) else filter
            }
            saveQualityFilters(next)
        }
    }

    fun deleteQualityFilter(filterId: String) {
        viewModelScope.launch {
            val next = _uiState.value.qualityFilters.filterNot { it.id == filterId }
            saveQualityFilters(next)
        }
    }

    private suspend fun saveQualityFilters(filters: List<QualityFilterConfig>) {
        context.settingsDataStore.edit { prefs ->
            prefs[qualityFiltersKey] = gson.toJson(filters)
        }
        // Device-scoped capability filter: intentionally local and not cloud-synced.
        _uiState.value = _uiState.value.copy(
            qualityFilters = filters,
            qualityFilterPresetLabel = detectQualityFilterPreset(filters).label
        )
        // Update in-memory cache in StreamRepository to avoid DataStore reads in hot path
        streamRepository.updateQualityFiltersCache(filters)
    }

    private fun detectQualityFilterPreset(filters: List<QualityFilterConfig>): QualityFilterPreset {
        val enabled = filters.filter { it.enabled && it.regexPattern.isNotBlank() }
        if (enabled.isEmpty()) return QualityFilterPreset.OFF
        if (enabled.size != 1) return QualityFilterPreset.CUSTOM

        val single = enabled.first()
        return QualityFilterPreset.entries.firstOrNull { preset ->
            preset != QualityFilterPreset.OFF &&
                preset != QualityFilterPreset.CUSTOM &&
                preset.filterId == single.id &&
                preset.regexPattern == single.regexPattern
        } ?: QualityFilterPreset.CUSTOM
    }

    // ========== Addon Management ==========
    
    fun toggleAddon(addonId: String) {
        viewModelScope.launch {
            streamRepository.toggleAddon(addonId)
            val addonsAfterToggle = streamRepository.installedAddons.first()
            runCatching {
                catalogRepository.syncAddonCatalogs(addonsAfterToggle)
            }
        }
    }
    
    fun addCustomAddon(url: String) {
        viewModelScope.launch {
            val result = streamRepository.addCustomAddon(url)
            result.onSuccess { addon ->
                // Small delay to let DataStore flush the write before reading back
                delay(150)
                val currentAddons = streamRepository.installedAddons.first()
                val importedCatalogs = addon.manifest?.catalogs?.size ?: 0
                runCatching {
                    catalogRepository.syncAddonCatalogs(currentAddons)
                }
                _uiState.value = _uiState.value.copy(
                    addons = currentAddons,
                    toastMessage = if (importedCatalogs > 0) {
                        "Added ${addon.name} ($importedCatalogs catalogs imported)"
                    } else {
                        "Added ${addon.name} (no catalogs exposed)"
                    },
                    toastType = ToastType.SUCCESS
                )
                }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = error.message?.takeIf { it.isNotBlank() } ?: "Failed to add addon",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun addCloudstreamRepository(url: String) {
        viewModelScope.launch {
            val result = streamRepository.addCloudstreamRepository(url)
            result.onSuccess { (normalizedRepoUrl, manifest, plugins) ->
                _uiState.value = _uiState.value.copy(
                    pendingCloudstreamManifest = manifest,
                    pendingCloudstreamRepoUrl = normalizedRepoUrl,
                    pendingCloudstreamPlugins = plugins,
                    toastMessage = "Loaded ${plugins.size} Cloudstream plugins from ${manifest.name}",
                    toastType = ToastType.SUCCESS
                )
                }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = error.message?.takeIf { it.isNotBlank() } ?: "Failed to load Cloudstream repository",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun dismissCloudstreamPluginPicker() {
        _uiState.value = _uiState.value.copy(
            pendingCloudstreamManifest = null,
            pendingCloudstreamRepoUrl = null,
            pendingCloudstreamPlugins = emptyList()
        )
    }

    fun installCloudstreamPlugin(plugin: CloudstreamPluginIndexEntry) {
        viewModelScope.launch {
            val repoUrl = _uiState.value.pendingCloudstreamRepoUrl
            val manifest = _uiState.value.pendingCloudstreamManifest
            if (repoUrl.isNullOrBlank() || manifest == null) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Cloudstream repository context expired. Try again.",
                    toastType = ToastType.ERROR
                )
                return@launch
            }
            val result = streamRepository.installCloudstreamPlugin(repoUrl, manifest, plugin)
            result.onSuccess { addon ->
                val addonsAfterInstall = streamRepository.installedAddons.first()
                _uiState.value = _uiState.value.copy(
                    addons = addonsAfterInstall,
                    toastMessage = "Installed ${addon.name}",
                    toastType = ToastType.SUCCESS
                )
                }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = error.message?.takeIf { it.isNotBlank() } ?: "Failed to install Cloudstream plugin",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun refreshCloudstreamRepository(repoUrl: String) {
        viewModelScope.launch {
            val result = streamRepository.refreshCloudstreamRepository(repoUrl)
            result.onSuccess { updateCount ->
                val suffix = if (updateCount > 0) "$updateCount plugin entries refreshed" else "No plugin updates found"
                _uiState.value = _uiState.value.copy(
                    toastMessage = suffix,
                    toastType = ToastType.SUCCESS
                )
                }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = error.message?.takeIf { it.isNotBlank() } ?: "Failed to refresh Cloudstream repository",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun removeCloudstreamRepository(repoUrl: String) {
        viewModelScope.launch {
            streamRepository.removeCloudstreamRepository(repoUrl)
            val addonsAfterRemove = streamRepository.installedAddons.first()
            _uiState.value = _uiState.value.copy(
                addons = addonsAfterRemove,
                toastMessage = "Removed Cloudstream repository",
                toastType = ToastType.SUCCESS
            )
        }
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            catalogRepository.observeCatalogs().collect { catalogs ->
                val effectiveCatalogs = if (catalogs.isEmpty()) {
                    catalogRepository.ensurePreinstalledDefaults(mediaRepository.getDefaultCatalogConfigs())
                } else {
                    catalogs
                }
                val visible = visibleCatalogs(effectiveCatalogs)
                if (_uiState.value.catalogs != visible) {
                    _uiState.value = _uiState.value.copy(catalogs = visible)
                }
            }
        }
    }

    private fun initializeCatalogs() {
        viewModelScope.launch {
            runCatching {
                catalogRepository.ensurePreinstalledDefaults(mediaRepository.getDefaultCatalogConfigs())
            }
        }
    }

    fun addCatalog(url: String) {
        viewModelScope.launch {
            val result = catalogRepository.addCustomCatalog(url)
            result.onSuccess { catalog ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Added ${catalog.title}",
                    toastType = ToastType.SUCCESS
                )
                }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = error.message ?: "Failed to add catalog",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun setCatalogSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(
            catalogSearchQuery = query,
            catalogSearchError = null
        )
    }

    fun searchCatalogLists(query: String = _uiState.value.catalogSearchQuery) {
        val normalizedQuery = query.trim()
        catalogSearchJob?.cancel()
        if (normalizedQuery.length < 2) {
            _uiState.value = _uiState.value.copy(
                catalogSearchResults = emptyList(),
                isCatalogSearching = false,
                catalogSearchError = if (normalizedQuery.isBlank()) null else "Type at least 2 characters"
            )
            return
        }

        catalogSearchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isCatalogSearching = true,
                catalogSearchError = null
            )
            val result = catalogDiscoveryRepository.searchCatalogLists(normalizedQuery)
            result.onSuccess { lists ->
                _uiState.value = _uiState.value.copy(
                    catalogSearchResults = lists,
                    isCatalogSearching = false,
                    catalogSearchError = if (lists.isEmpty()) "No public Trakt lists found" else null
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    catalogSearchResults = emptyList(),
                    isCatalogSearching = false,
                    catalogSearchError = error.message ?: "Failed to search catalogs"
                )
            }
        }
    }

    fun clearCatalogDiscovery() {
        catalogSearchJob?.cancel()
        catalogSearchJob = null
        _uiState.value = _uiState.value.copy(
            catalogSearchQuery = "",
            catalogSearchResults = emptyList(),
            isCatalogSearching = false,
            catalogSearchError = null
        )
    }

    fun addDiscoveredCatalog(result: CatalogDiscoveryResult) {
        viewModelScope.launch {
            val addResult = catalogRepository.addCustomCatalog(result.sourceUrl)
            addResult.onSuccess { catalog ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Added ${catalog.title}",
                    toastType = ToastType.SUCCESS
                )
                }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = error.message ?: "Failed to add catalog",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun updateCatalog(catalogId: String, url: String) {
        viewModelScope.launch {
            val result = catalogRepository.updateCustomCatalog(catalogId, url)
            result.onSuccess { catalog ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Updated ${catalog.title}",
                    toastType = ToastType.SUCCESS
                )
                }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = error.message ?: "Failed to update catalog",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun removeCatalog(catalogId: String) {
        viewModelScope.launch {
            val result = catalogRepository.removeCustomCatalog(catalogId)
            result.onSuccess {
                // Refresh the catalog list in UI state after removal
                val updatedCatalogs = visibleCatalogs(catalogRepository.getCatalogs())
                _uiState.value = _uiState.value.copy(
                    catalogs = updatedCatalogs,
                    toastMessage = "Catalog removed",
                    toastType = ToastType.SUCCESS
                )
                }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = error.message ?: "Failed to remove catalog",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun renameCatalog(catalogId: String, newTitle: String) {
        viewModelScope.launch {
            val success = catalogRepository.renameCatalog(catalogId, newTitle)
            if (success) {
                }
        }
    }

    fun moveCatalogUp(catalogId: String) {
        viewModelScope.launch {
            catalogRepository.moveCatalogUp(catalogId)
        }
    }

    fun moveCatalogDown(catalogId: String) {
        viewModelScope.launch {
            catalogRepository.moveCatalogDown(catalogId)
        }
    }

    fun removeAddon(addonId: String) {
        viewModelScope.launch {
            streamRepository.removeAddon(addonId)
            val addonsAfterRemove = streamRepository.installedAddons.first()
            runCatching {
                catalogRepository.syncAddonCatalogs(addonsAfterRemove)
            }
        }
    }

    fun setTorrServerBaseUrl(url: String) {
        viewModelScope.launch {
            streamRepository.setTorrServerBaseUrl(url)
            // No cloud sync needed; this is a local playback setting.
        }
    }

    fun testTorrServer() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(torrServerStatus = TorrServerStatus.TESTING)
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                testTorrServerConnection(_uiState.value.torrServerBaseUrl)
            }
            _uiState.value = _uiState.value.copy(torrServerStatus = result)
        }
    }

    fun autoDetectTorrServer() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(torrServerStatus = TorrServerStatus.TESTING)
            val candidates = buildList {
                val configured = _uiState.value.torrServerBaseUrl.trim().removeSuffix("/")
                if (configured.isNotBlank()) add(configured)
                add("http://127.0.0.1:8090")
                add("http://localhost:8090")
                add("http://192.168.1.1:8090")
            }
            var found: String? = null
            for (url in candidates) {
                val status = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    testTorrServerConnection(url)
                }
                if (status == TorrServerStatus.CONNECTED) {
                    found = url
                    break
                }
            }
            if (found != null) {
                streamRepository.setTorrServerBaseUrl(found)
                _uiState.value = _uiState.value.copy(
                    torrServerBaseUrl = found,
                    torrServerStatus = TorrServerStatus.CONNECTED
                )
            } else {
                _uiState.value = _uiState.value.copy(torrServerStatus = TorrServerStatus.UNREACHABLE)
            }
        }
    }

    private suspend fun testTorrServerConnection(baseUrl: String): TorrServerStatus {
        if (baseUrl.isBlank()) return TorrServerStatus.UNREACHABLE
        val url = baseUrl.removeSuffix("/")
        return try {
            val response = withTimeoutOrNull(5000L) {
                val okUrl = java.net.URL("$url/echo")
                val conn = okUrl.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.responseCode
            }
            if (response != null && response in 200..299) TorrServerStatus.CONNECTED
            else TorrServerStatus.UNREACHABLE
        } catch (_: Exception) {
            TorrServerStatus.UNREACHABLE
        }
    }


    fun checkForAppUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isCheckingForUpdate = true,
                appUpdateError = null,
                showAppUpdateDialog = false
            )

            val ignoredTag = updatePreferences.ignoredTag.first()
            val result = appUpdateRepository.getLatestUpdate()
            updatePreferences.setLastCheckAtMs(System.currentTimeMillis())

            result
                .onSuccess { update ->
                    // Use the actually installed version from PackageManager, not BuildConfig,
                    // because on Android TV the old process can survive an APK install.
                    val installedVersion = appUpdateRepository.getInstalledVersionName()
                    val remoteNewer = VersionUtils.isRemoteNewer(update.tag, installedVersion)
                    val shouldShow = remoteNewer && (ignoredTag == null || ignoredTag != update.tag)

                    _uiState.value = _uiState.value.copy(
                        isCheckingForUpdate = false,
                        availableAppUpdate = update,
                        isAppUpdateAvailable = remoteNewer,
                        isDownloadingAppUpdate = false,
                        appUpdateDownloadProgress = null,
                        downloadedApkPath = if (remoteNewer) _uiState.value.downloadedApkPath else null,
                        showAppUpdateDialog = shouldShow || force,
                        appUpdateError = null,
                        toastMessage = if (showNoUpdateFeedback && !remoteNewer) "You already have the latest version" else _uiState.value.toastMessage,
                        toastType = if (showNoUpdateFeedback && !remoteNewer) ToastType.INFO else _uiState.value.toastType
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isCheckingForUpdate = false,
                        availableAppUpdate = null,
                        isAppUpdateAvailable = false,
                        showAppUpdateDialog = force,
                        appUpdateError = error.message ?: "Update check failed"
                    )
                }
        }
    }

    fun dismissAppUpdateDialog() {
        _uiState.value = _uiState.value.copy(showAppUpdateDialog = false, showUnknownSourcesDialog = false, appUpdateError = null)
    }

    fun ignoreAppUpdate() {
        viewModelScope.launch {
            updatePreferences.setIgnoredTag(_uiState.value.availableAppUpdate?.tag)
            _uiState.value = _uiState.value.copy(showAppUpdateDialog = false)
        }
    }

    fun downloadAppUpdate() {
        val update = _uiState.value.availableAppUpdate ?: return
        if (!_uiState.value.isAppUpdateAvailable) {
            _uiState.value = _uiState.value.copy(
                toastMessage = "You already have the latest version",
                toastType = ToastType.INFO,
                showAppUpdateDialog = true,
                downloadedApkPath = null
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloadingAppUpdate = true,
                appUpdateDownloadProgress = 0f,
                appUpdateError = null,
                showAppUpdateDialog = true
            )

            val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(File(context.cacheDir, "updates"), safeName)
            val result = withContext(Dispatchers.IO) {
                apkDownloader.download(update.assetUrl, dest) { downloaded, total ->
                    val progress = if (total != null && total > 0L) {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    _uiState.value = _uiState.value.copy(appUpdateDownloadProgress = progress)
                }
            }

            result
                .onSuccess { file ->
                    _uiState.value = _uiState.value.copy(
                        isDownloadingAppUpdate = false,
                        appUpdateDownloadProgress = 1f,
                        downloadedApkPath = file.absolutePath,
                        appUpdateError = null,
                        showAppUpdateDialog = true
                    )
                    installAppUpdateOrRequestPermission()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isDownloadingAppUpdate = false,
                        appUpdateDownloadProgress = null,
                        downloadedApkPath = null,
                        appUpdateError = error.message ?: "Download failed",
                        showAppUpdateDialog = true
                    )
                }
        }
    }

    fun installAppUpdateOrRequestPermission() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            _uiState.value = _uiState.value.copy(appUpdateError = "Downloaded file is missing", showAppUpdateDialog = true)
            return
        }

        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.value = _uiState.value.copy(showUnknownSourcesDialog = true, showAppUpdateDialog = false)
            return
        }

        // Check for signature conflict before installing
        val conflictMsg = ApkInstaller.checkSignatureConflict(context, apkFile)
        if (conflictMsg != null) {
            _uiState.value = _uiState.value.copy(appUpdateError = conflictMsg, showAppUpdateDialog = true)
            return
        }

        ApkInstaller.launchInstall(context, apkFile)
        // Mark this release as "installed" so we don't re-show the update after the
        // system installer returns the user to the old still-running process.
        viewModelScope.launch {
            _uiState.value.availableAppUpdate?.tag?.let { tag ->
                updatePreferences.setIgnoredTag(tag)
            }
        }
        _uiState.value = _uiState.value.copy(
            downloadedApkPath = null,
            showAppUpdateDialog = false,
            isAppUpdateAvailable = false,
            toastMessage = "Installing update...",
            toastType = ToastType.INFO
        )
    }

    fun openUnknownSourcesSettings() {
        ApkInstaller.buildUnknownSourcesSettingsIntent(context)?.let { intent ->
            context.startActivity(intent)
        }
    }
    
    // ========== Trakt Authentication ==========
    
    fun startTraktAuth() {
        val current = _uiState.value
        if (current.isTraktAuthStarting || current.isTraktPolling) return

        viewModelScope.launch {
            traktPollingJob?.cancel()
            _uiState.value = _uiState.value.copy(
                traktCode = null,
                isTraktAuthStarting = true,
                isTraktPolling = false,
                toastMessage = null
            )

            try {
                val deviceCode = withContext(Dispatchers.IO) {
                    traktRepository.getDeviceCode()
                }
                _uiState.value = _uiState.value.copy(
                    traktCode = deviceCode,
                    isTraktAuthStarting = false,
                    isTraktAuthenticated = false,
                    isTraktPolling = true
                )

                // Start polling for token
                startTraktPolling(deviceCode)
            } catch (e: Exception) {
                System.err.println("SettingsVM: failed to start Trakt auth: ${e.message}")
                val message = when (e) {
                    is retrofit2.HttpException -> "Trakt activation failed (${e.code()})"
                    else -> e.message?.takeIf { it.isNotBlank() } ?: "Trakt activation failed"
                }
                _uiState.value = _uiState.value.copy(
                    traktCode = null,
                    isTraktAuthStarting = false,
                    isTraktPolling = false,
                    toastMessage = message,
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun reconnectTrakt() {
        viewModelScope.launch {
            cancelTraktAuth()
            traktRepository.logout()
            _uiState.value = _uiState.value.copy(
                isTraktAuthenticated = false,
                traktExpiration = null
            )
            startTraktAuth()
        }
    }

    private fun startTraktPolling(deviceCode: TraktDeviceCode) {
        traktPollingJob?.cancel()
        traktPollingJob = viewModelScope.launch {
            val expiresAt = System.currentTimeMillis() + (deviceCode.expiresIn * 1000)
            var lastFailure: String? = null
            
            while (System.currentTimeMillis() < expiresAt) {
                delay(deviceCode.interval * 1000L)
                
                try {
                    traktRepository.pollForToken(deviceCode.deviceCode)

                    // Get the expiration date
                    val expirationDate = traktRepository.getTokenExpirationDate()

                    // Success!
                    _uiState.value = _uiState.value.copy(
                        isTraktAuthenticated = true,
                        traktCode = null,
                        isTraktAuthStarting = false,
                        isTraktPolling = false,
                        traktExpiration = expirationDate,
                        toastMessage = "Trakt connected successfully",
                        toastType = ToastType.SUCCESS
                    )
                    traktRepository.clearContinueWatchingCache()
                    runCatching { traktRepository.getContinueWatching() }
                    performFullSync(silent = true)
                            runCatching { launcherContinueWatchingRepository.refreshForCurrentProfile() }
                    return@launch
                } catch (e: Exception) {
                    // Keep polling on 400 (pending) - user hasn't entered code yet
                    // Check both HttpException code and message for 400
                    val is400 = when (e) {
                        is retrofit2.HttpException -> e.code() == 400
                        else -> e.message?.contains("400") == true ||
                                e.message?.contains("pending") == true
                    }
                    if (!is400) {
                        lastFailure = when (e) {
                            is retrofit2.HttpException -> "Trakt authorization failed (${e.code()})"
                            else -> e.message?.takeIf { it.isNotBlank() } ?: "Trakt authorization failed"
                        }
                        break
                    }
                    // 400 = pending, continue polling
                }
            }
            
            // Expired or failed
            _uiState.value = _uiState.value.copy(
                traktCode = null,
                isTraktAuthStarting = false,
                isTraktPolling = false,
                toastMessage = lastFailure ?: "Trakt activation code expired",
                toastType = ToastType.ERROR
            )
        }
    }
    
    fun cancelTraktAuth() {
        traktPollingJob?.cancel()
        _uiState.value = _uiState.value.copy(
            traktCode = null,
            isTraktAuthStarting = false,
            isTraktPolling = false
        )
    }
    
    fun disconnectTrakt() {
        viewModelScope.launch {
            cancelTraktAuth()
            traktRepository.logout()
            _uiState.value = _uiState.value.copy(
                isTraktAuthenticated = false,
                traktExpiration = null,
                toastMessage = "Trakt disconnected",
                toastType = ToastType.SUCCESS
            )
        }
    }

    fun dismissToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        traktPollingJob?.cancel()
    }
}