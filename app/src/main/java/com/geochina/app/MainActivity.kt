package com.geochina.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.geochina.app.data.AdminRepository
import com.geochina.app.data.ChinaAdminDataset
import com.geochina.app.data.GeoChinaDatabase
import com.geochina.app.data.SearchHistoryStore
import com.geochina.app.ui.GeoChinaRoute
import com.geochina.app.ui.GeoChinaViewModel
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        MapLibre.getInstance(this)
        OfflineManager.getInstance(this).setMaximumAmbientCacheSize(256L * 1024L * 1024L, null)
        ChinaAdminDataset.initialize(this)
        val database = GeoChinaDatabase.get(this)
        val viewModel = ViewModelProvider(
            this,
            GeoChinaViewModel.Factory(
                repository = AdminRepository(database.favoriteRegionDao()),
                historyStore = SearchHistoryStore(this),
            ),
        )[GeoChinaViewModel::class.java]

        setContent {
            GeoChinaRoute(viewModel = viewModel)
        }
    }
}
