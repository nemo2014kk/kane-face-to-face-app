package com.example.firstapp

object AppConstants {
    // 🌟 全量自动推荐默认音色映射表 (精准同步 Python)
    val DEFAULT_VOICE_MAP = mapOf(
        "中文" to "晓晓 (中&英·温柔女声)", "粤语" to "晓佳 (粤&英·温柔女声)", "英语" to "Ava (英文·自然女声)",
        "日语" to "Nanami (日语·甜美女声)", "韩语" to "Sun-Hi (韩语·优雅女声)", "法语" to "Denise (法语·清澈女声)",
        "德语" to "Katja (德语·干练女声)", "西班牙语" to "Elvira (西班牙语·成熟女声)", "斯洛伐克语" to "Viktoria (斯洛伐克语·女声)",
        "俄语" to "Svetlana (俄语·知性女声)", "乌克兰语" to "Polina (乌克兰语·自然女声)", "亚美尼亚语" to "Anahit (亚美尼亚语·暂无语音)",
        "格鲁吉亚语" to "Eka (格鲁吉亚语·柔和女声)", "克罗地亚语" to "Gabrijela (克罗地亚语·女声)", "意大利语" to "Elsa (意大利语·热情女声)",
        "葡萄牙语" to "Francisca (葡萄牙语·大方女声)", "阿拉伯语" to "Zariyah (阿拉伯语·典雅女声)", "泰语" to "Premwadee (泰语·温柔女声)",
        "蒙古语" to "Yesui (蒙古语·女声)", "印尼语" to "Gadis (印尼语·亲切女声)", "印地语" to "Swara (印地语·自然女声)",
        "菲律宾语" to "Blessica (菲律宾语·甜美女声)", "匈牙利语" to "Noemi (匈牙利语·知性女声)", "孟加拉语" to "Nabanita (孟加拉语·温婉女声)",
        "越南语" to "HoaiMy (越南语·柔美女声)", "波兰语" to "Zofia (波兰语·知性女声)", "荷兰语" to "Fenna (荷兰语·亲切女声)",
        "瑞典语" to "Sofie (瑞典语·甜美女声)", "斯瓦希里语" to "Zuri (斯瓦希里语·淳朴女声)", "立陶宛语" to "Ona (立陶宛语·温和女声)",
        // 👇 新加的塞尔维亚语默认音色
        "塞尔维亚语" to "Sophie (塞尔维亚语·柔美女声)"
    )

    val LANG_GROUPS = mapOf(
        "亚洲" to listOf("中文", "粤语", "日语", "韩语", "泰语", "越南语", "蒙古语", "印尼语", "印地语", "孟加拉语", "菲律宾语"),
        // 👇 在欧美列表的最后面，加上了 "塞尔维亚语"
        "欧美" to listOf("英语", "法语", "德语", "西班牙语", "斯洛伐克语", "葡萄牙语", "意大利语", "俄语", "乌克兰语", "亚美尼亚语", "格鲁吉亚语", "克罗地亚语", "匈牙利语", "波兰语", "荷兰语", "瑞典语", "立陶宛语", "塞尔维亚语"),
        "中东与非洲" to listOf("阿拉伯语", "斯瓦希里语")
    )

    val LANG_MAP_EN = mapOf(
        "中文" to "Chinese", "粤语" to "Cantonese", "日语" to "Japanese", "韩语" to "Korean",
        "泰语" to "Thai", "越南语" to "Vietnamese", "蒙古语" to "Mongolian", "印尼语" to "Indonesian",
        "印地语" to "Hindi", "孟加拉语" to "Bengali", "菲律宾语" to "Filipino", "英语" to "English",
        "法语" to "French", "德语" to "German", "西班牙语" to "Spanish", "斯洛伐克语" to "Slovak",
        "葡萄牙语" to "Portuguese", "意大利语" to "Italian", "俄语" to "Russian", "乌克兰语" to "Ukrainian",
        "亚美尼亚语" to "Armenian", "格鲁吉亚语" to "Georgian", "克罗地亚语" to "Croatian",
        "匈牙利语" to "Hungarian", "波兰语" to "Polish", "荷兰语" to "Dutch", "瑞典语" to "Swedish",
        "立陶宛语" to "Lithuanian", "阿拉伯语" to "Arabic", "斯瓦希里语" to "Swahili",
        // 👇 新加的塞尔维亚语英文名 (大模型翻译需要用到)
        "塞尔维亚语" to "Serbian"
    )

    // 🌟 全量底层映射表 (同步 Python 13.4)
    val LANG_CODES = mapOf(
        "中文" to "zh", "粤语" to "zh", "英语" to "en", "斯洛伐克语" to "sk", "日语" to "ja", "韩语" to "ko", "法语" to "fr",
        "德语" to "de", "西班牙语" to "es", "俄语" to "ru", "乌克兰语" to "uk", "亚美尼亚语" to "hy", "格鲁吉亚语" to "ka",
        "克罗地亚语" to "hr", "意大利语" to "it", "斯瓦希里语" to "sw", "葡萄牙语" to "pt", "阿拉伯语" to "ar",
        "泰语" to "th", "蒙古语" to "mn", "印尼语" to "id", "印地语" to "hi", "菲律宾语" to "tl",
        "匈牙利语" to "hu", "孟加拉语" to "bn", "越南语" to "vi", "波兰语" to "pl", "荷兰语" to "nl",
        "瑞典语" to "sv", "立陶宛语" to "lt",
        // 👇 新加的塞尔维亚语音频代号 (Whisper识别需要用到)
        "塞尔维亚语" to "sr"
    )

    val TTS_GROUPS = mapOf(
        "常用 (中/英)" to listOf("晓晓 (中&英·温柔女声)", "晓依 (中&英·活泼女声)", "云希 (中&英·阳光男声)", "云健 (中&英·沉稳男声)", "Ava (英文·自然女声)", "Emma (英文·职场女声)", "Andrew (英文·商务男声)", "Brian (英文·成熟男声)"),
        "粤语 (中国香港)" to listOf("晓佳 (粤&英·温柔女声)", "晓曼 (粤&英·活泼女声)", "云龙 (粤&英·沉稳男声)"),
        "亚洲语系" to listOf("Nanami (日语·甜美女声)", "Keita (日语·活力男声)", "Sun-Hi (韩语·优雅女声)", "InJoon (韩语·磁性男声)", "Premwadee (泰语·温柔女声)", "Niwat (泰语·沉稳男声)", "HoaiMy (越南语·柔美女声)", "NamMinh (越南语·稳重男声)", "Swara (印地语·自然女声)", "Madhur (印地语·平和男声)", "Blessica (菲律宾语·甜美女声)", "Angelo (菲律宾语·阳光男声)", "Nabanita (孟加拉语·温婉女声)", "Bashkar (孟加拉语·坚朗男声)", "Yesui (蒙古语·女声)", "Gadis (印尼语·亲切女声)", "Ardi (印尼语·清爽男声)"),
        // 👇 在下面这行“欧洲语系”的最末尾，加上了塞尔维亚语的两个发音人
        "欧洲语系" to listOf("Denise (法语·清澈女声)", "Eloise (法语·温柔女声)", "Henri (法语·庄重男声)", "Katja (德语·干练女声)", "Conrad (德语·稳重男声)", "Elvira (西班牙语·成熟女声)", "Alvaro (西班牙语·磁性男声)", "Viktoria (斯洛伐克语·女声)", "Lukas (斯洛伐克语·男声)", "Elsa (意大利语·热情女声)", "Isabella (意大利语·柔美女声)", "Diego (意大利语·儒雅男声)", "Francisca (葡萄牙语·大方女声)", "Antonio (葡萄牙语·活力男声)", "Svetlana (俄语·知性女声)", "Dmitry (俄语·浑厚男声)", "Polina (乌克兰语·自然女声)", "Ostap (乌克兰语·深沉男声)", "Anahit (亚美尼亚语·暂无语音)", "Hayk (亚美尼亚语·暂无语音)", "Eka (格鲁吉亚语·柔和女声)", "Giorgi (格鲁吉亚语·沉稳男声)", "Gabrijela (克罗地亚语·女声)", "Srecko (克罗地亚语·男声)", "Noemi (匈牙利语·知性女声)", "Tamas (匈牙利语·稳重男声)", "Zofia (波兰语·知性女声)", "Marek (波兰语·磁性男声)", "Fenna (荷兰语·亲切女声)", "Maarten (荷兰语·商务男声)", "Sofie (瑞典语·甜美女声)", "Mattias (瑞典语·活力男声)", "Ona (立陶宛语·温和女声)", "Leonas (立陶宛语·沉稳男声)", "Sophie (塞尔维亚语·柔美女声)", "Nicholas (塞尔维亚语·沉稳男声)"),
        "其他" to listOf("Zariyah (阿拉伯语·典雅女声)", "Hamed (阿拉伯语·坚毅男声)", "Zuri (斯瓦希里语·淳朴女声)", "Rafiki (斯瓦希里语·睿智男声)")
    )

    val TTS_VOICES = mapOf(
        "晓晓 (中&英·温柔女声)" to "zh-CN-XiaoxiaoNeural", "晓依 (中&英·活泼女声)" to "zh-CN-XiaoyiNeural",
        "云希 (中&英·阳光男声)" to "zh-CN-YunxiNeural", "云健 (中&英·沉稳男声)" to "zh-CN-YunjianNeural",
        "晓佳 (粤&英·温柔女声)" to "zh-HK-HiuGaaiNeural", "晓曼 (粤&英·活泼女声)" to "zh-HK-HiuMaanNeural",
        "云龙 (粤&英·沉稳男声)" to "zh-HK-WanLungNeural",
        "Ava (英文·自然女声)" to "en-US-AvaNeural", "Emma (英文·职场女声)" to "en-US-EmmaNeural",
        "Andrew (英文·商务男声)" to "en-US-AndrewNeural", "Brian (英文·成熟男声)" to "en-US-BrianNeural",
        "Nanami (日语·甜美女声)" to "ja-JP-NanamiNeural", "Keita (日语·活力男声)" to "ja-JP-KeitaNeural",
        "Sun-Hi (韩语·优雅女声)" to "ko-KR-SunHiNeural", "InJoon (韩语·磁性男声)" to "ko-KR-InJoonNeural",
        "Denise (法语·清澈女声)" to "fr-FR-DeniseNeural", "Eloise (法语·温柔女声)" to "fr-FR-EloiseNeural",
        "Henri (法语·庄重男声)" to "fr-FR-HenriNeural",
        "Katja (德语·干练女声)" to "de-DE-KatjaNeural", "Conrad (德语·稳重男声)" to "de-DE-ConradNeural",
        "Elvira (西班牙语·成熟女声)" to "es-ES-ElviraNeural", "Alvaro (西班牙语·磁性男声)" to "es-ES-AlvaroNeural",
        "Viktoria (斯洛伐克语·女声)" to "sk-SK-ViktoriaNeural", "Lukas (斯洛伐克语·男声)" to "sk-SK-LukasNeural",
        "Elsa (意大利语·热情女声)" to "it-IT-ElsaNeural", "Isabella (意大利语·柔美女声)" to "it-IT-IsabellaNeural",
        "Diego (意大利语·儒雅男声)" to "it-IT-DiegoNeural",
        "Francisca (葡萄牙语·大方女声)" to "pt-BR-FranciscaNeural", "Antonio (葡萄牙语·活力男声)" to "pt-BR-AntonioNeural",
        "Svetlana (俄语·知性女声)" to "ru-RU-SvetlanaNeural", "Dmitry (俄语·浑厚男声)" to "ru-RU-DmitryNeural",
        "Polina (乌克兰语·自然女声)" to "uk-UA-PolinaNeural", "Ostap (乌克兰语·深沉男声)" to "uk-UA-OstapNeural",
        "Anahit (亚美尼亚语·暂无语音)" to "hy-AM-AnahitNeural", "Hayk (亚美尼亚语·暂无语音)" to "hy-AM-HaykNeural",
        "Eka (格鲁吉亚语·柔和女声)" to "ka-GE-EkaNeural", "Giorgi (格鲁吉亚语·沉稳男声)" to "ka-GE-GiorgiNeural",
        "Gabrijela (克罗地亚语·女声)" to "hr-HR-GabrijelaNeural", "Srecko (克罗地亚语·男声)" to "hr-HR-SreckoNeural",
        "Noemi (匈牙利语·知性女声)" to "hu-HU-NoemiNeural", "Tamas (匈牙利语·稳重男声)" to "hu-HU-TamasNeural",
        "Zofia (波兰语·知性女声)" to "pl-PL-ZofiaNeural", "Marek (波兰语·磁性男声)" to "pl-PL-MarekNeural",
        "Fenna (荷兰语·亲切女声)" to "nl-NL-FennaNeural", "Maarten (荷兰语·商务男声)" to "nl-NL-MaartenNeural",
        "Sofie (瑞典语·甜美女声)" to "sv-SE-SofieNeural", "Mattias (瑞典语·活力男声)" to "sv-SE-MattiasNeural",
        "Ona (立陶宛语·温和女声)" to "lt-LT-OnaNeural", "Leonas (立陶宛语·沉稳男声)" to "lt-LT-LeonasNeural",
        "Zariyah (阿拉伯语·典雅女声)" to "ar-SA-ZariyahNeural", "Hamed (阿拉伯语·坚毅男声)" to "ar-SA-HamedNeural",
        "Premwadee (泰语·温柔女声)" to "th-TH-PremwadeeNeural", "Niwat (泰语·沉稳男声)" to "th-TH-NiwatNeural",
        "HoaiMy (越南语·柔美女声)" to "vi-VN-HoaiMyNeural", "NamMinh (越南语·稳重男声)" to "vi-VN-NamMinhNeural",
        "Swara (印地语·自然女声)" to "hi-IN-SwaraNeural", "Madhur (印地语·平和男声)" to "hi-IN-MadhurNeural",
        "Blessica (菲律宾语·甜美女声)" to "fil-PH-BlessicaNeural", "Angelo (菲律宾语·阳光男声)" to "fil-PH-AngeloNeural",
        "Nabanita (孟加拉语·温婉女声)" to "bn-BD-NabanitaNeural", "Bashkar (孟加拉语·坚朗男声)" to "bn-BD-BashkarNeural",
        "Yesui (蒙古语·女声)" to "mn-MN-YesuiNeural", "Gadis (印尼语·亲切女声)" to "id-ID-GadisNeural", "Ardi (印尼语·清爽男声)" to "id-ID-ArdiNeural",
        "Zuri (斯瓦希里语·淳朴女声)" to "sw-KE-ZuriNeural", "Rafiki (斯瓦希里语·睿智男声)" to "sw-KE-RafikiNeural",
        // 👇 新加的塞尔维亚语发音人映射
        "Sophie (塞尔维亚语·柔美女声)" to "sr-RS-SophieNeural", "Nicholas (塞尔维亚语·沉稳男声)" to "sr-RS-NicholasNeural"
    )

    // 🌟 核心修复：基于音色底层 ID 反向精准推导语种 (杜绝因为含有"中&英"等模糊词导致的错误联动)
    // 🌟 核心修复：基于音色底层 ID 反向精准推导语种 (杜绝因为含有"中&英"等模糊词导致的错误联动)
    val VOICE_PREFIX_TO_LANG = mapOf(
        "zh-CN" to "中文", "zh-HK" to "粤语", "en-" to "英语", "ja-" to "日语",
        "ko-" to "韩语", "fr-" to "法语", "de-" to "德语", "es-" to "西班牙语",
        "sk-" to "斯洛伐克语", "ru-" to "俄语", "uk-" to "乌克兰语", "hy-" to "亚美尼亚语",
        "ka-" to "格鲁吉亚语", "hr-" to "克罗地亚语", "it-" to "意大利语", "pt-" to "葡萄牙语",
        "ar-" to "阿拉伯语", "th-" to "泰语", "mn-" to "蒙古语", "id-" to "印尼语",
        "hi-" to "印地语", "fil-" to "菲律宾语", "hu-" to "匈牙利语", "bn-" to "孟加拉语",
        "vi-" to "越南语", "pl-" to "波兰语", "nl-" to "荷兰语", "sv-" to "瑞典语",
        "sw-" to "斯瓦希里语", "lt-" to "立陶宛语",
        // 👇 新加的塞尔维亚语前缀
        "sr-" to "塞尔维亚语"
    )

    fun getFlatLangList(): List<String> {
        val list = mutableListOf<String>()
        LANG_GROUPS.forEach { (group, items) -> list.add("━━ $group ━━"); list.addAll(items) }
        return list
    }

    fun getFlatVoiceList(): List<String> {
        val list = mutableListOf<String>()
        TTS_GROUPS.forEach { (group, items) -> list.add("━━ $group ━━"); list.addAll(items) }
        return list
    }
}