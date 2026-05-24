package com.geochina.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.geochina.app.data.AdminRepository
import com.geochina.app.data.ChinaAdminDataset
import com.geochina.app.data.GeoChinaDatabase
import com.geochina.app.data.SearchHistoryStore
import com.geochina.app.ui.GeoChinaRoute
import com.geochina.app.ui.GeoChinaViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
