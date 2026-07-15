package com.weike.ime.data

import android.content.Context

class AppContainer(context: Context) {
    val settings = AppSettingsRepository(context.applicationContext)
    val lexicon = LexiconDatabase.get(context.applicationContext).lexiconDao()
    val englishLearning = LexiconDatabase.get(context.applicationContext).englishLearningDao()
    val typingDictionary = LexiconDatabase.get(context.applicationContext).typingDictionaryDao()
    val usageStats = LexiconDatabase.get(context.applicationContext).usageStatsDao()
    val inputHistory = LexiconDatabase.get(context.applicationContext).inputHistoryDao()
    val clipboard = LexiconDatabase.get(context.applicationContext).clipboardDao()
}
