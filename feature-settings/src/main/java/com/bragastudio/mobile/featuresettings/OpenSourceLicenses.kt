package com.bragastudio.mobile.featuresettings

/**
 * Componente de terceiros distribuído com o app (item 5.9 / L12).
 *
 * @property name nome exibido (a lista é mantida em ordem alfabética, sem diferenciar maiúsculas)
 * @property version versão declarada em gradle/libs.versions.toml, ou a origem quando é transitiva/nativa
 * @property license nome curto da licença (SPDX quando existe)
 * @property url página do projeto — de onde também se obtém o código-fonte
 * @property notice aviso de atribuição / observação de licença exibido ao expandir o item
 */
data class OpenSourceLicense(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
    val notice: String,
)

/**
 * Lista escrita à mão a partir de gradle/libs.versions.toml e dos build.gradle.kts dos módulos.
 * Ao adicionar/atualizar uma dependência de runtime, atualize aqui (o teste garante campos
 * preenchidos e a ordem alfabética).
 */
object OpenSourceLicenses {

    /** Aviso LGPL exibido no topo da tela. */
    const val LGPL_NOTE: String =
        "Este app usa a biblioteca libusb, licenciada sob a LGPL-2.1 (distribuída com o UVCAndroid). " +
            "Você tem o direito de substituí-la por uma versão modificada ou própria e de obter o " +
            "código-fonte completo no site do projeto (https://libusb.info e " +
            "https://github.com/libusb/libusb). Os demais componentes são usados conforme as licenças abaixo."

    private const val APACHE = "Apache-2.0"
    private const val APACHE_NOTICE =
        "Licenciado sob a Apache License, Versão 2.0. Você pode obter uma cópia em " +
            "https://www.apache.org/licenses/LICENSE-2.0. O software é distribuído \"como está\", sem garantias."

    val all: List<OpenSourceLicense> = listOf(
        OpenSourceLicense(
            "AndroidX Activity Compose", "1.12.4", APACHE,
            "https://developer.android.com/jetpack/androidx/releases/activity",
            "Copyright The Android Open Source Project. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "AndroidX Core KTX", "1.17.0", APACHE,
            "https://developer.android.com/jetpack/androidx/releases/core",
            "Copyright The Android Open Source Project. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "AndroidX DataStore", "1.2.1", APACHE,
            "https://developer.android.com/jetpack/androidx/releases/datastore",
            "Copyright The Android Open Source Project. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "AndroidX DocumentFile", "1.1.0", APACHE,
            "https://developer.android.com/jetpack/androidx/releases/documentfile",
            "Copyright The Android Open Source Project. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "AndroidX Hilt Navigation Compose", "1.3.0", APACHE,
            "https://developer.android.com/jetpack/androidx/releases/hilt",
            "Copyright The Android Open Source Project. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "AndroidX Lifecycle", "2.10.0", APACHE,
            "https://developer.android.com/jetpack/androidx/releases/lifecycle",
            "Copyright The Android Open Source Project. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "AndroidX Navigation Compose", "2.9.8", APACHE,
            "https://developer.android.com/jetpack/androidx/releases/navigation",
            "Copyright The Android Open Source Project. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "AndroidX Room", "2.8.5", APACHE,
            "https://developer.android.com/jetpack/androidx/releases/room",
            "Copyright The Android Open Source Project. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "AndroidX SQLite", "2.4.0", APACHE,
            "https://developer.android.com/jetpack/androidx/releases/sqlite",
            "Copyright The Android Open Source Project. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "Coil", "2.7.0", APACHE,
            "https://github.com/coil-kt/coil",
            "Copyright Coil Contributors. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "Dagger e Hilt", "2.58", APACHE,
            "https://github.com/google/dagger",
            "Copyright The Dagger Authors. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "hutool-core", "5.6.3 (transitiva do UVCAndroid)", "Mulan PSL v2",
            "https://github.com/dromara/hutool",
            "Biblioteca utilitária Java do projeto Hutool, trazida como dependência do UVCAndroid. " +
                "Licenciada sob a Mulan Permissive Software License, Versão 2 " +
                "(https://license.coscl.org.cn/MulanPSL2).",
        ),
        OpenSourceLicense(
            "Jetpack Compose, Material 3 e Material Icons", "BOM 2025.12.01", APACHE,
            "https://developer.android.com/jetpack/compose",
            "Copyright The Android Open Source Project. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "Kotlin", "2.3.21", APACHE,
            "https://github.com/JetBrains/kotlin",
            "Copyright JetBrains s.r.o. e contribuidores do Kotlin. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "kotlinx.coroutines", "1.11.0", APACHE,
            "https://github.com/Kotlin/kotlinx.coroutines",
            "Copyright JetBrains s.r.o. e contribuidores. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "kotlinx.serialization", "1.11.0", APACHE,
            "https://github.com/Kotlin/kotlinx.serialization",
            "Copyright JetBrains s.r.o. e contribuidores. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "Ktor Server", "2.3.13", APACHE,
            "https://github.com/ktorio/ktor",
            "Copyright JetBrains s.r.o. e contribuidores do Ktor. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "libjpeg-turbo", "incluída no UVCAndroid", "BSD-3-Clause / IJG / zlib",
            "https://libjpeg-turbo.org",
            "Copyright The libjpeg-turbo Project e Independent JPEG Group. Este software é baseado em parte " +
                "no trabalho do Independent JPEG Group. Redistribuição sob a licença BSD de 3 cláusulas, " +
                "a IJG License e a zlib License.",
        ),
        OpenSourceLicense(
            "libusb", "incluída no UVCAndroid", "LGPL-2.1",
            "https://libusb.info",
            "Copyright dos contribuidores do libusb. Licenciada sob a GNU Lesser General Public License v2.1. " +
                "Você pode substituir esta biblioteca por uma versão modificada e obter o código-fonte em " +
                "https://github.com/libusb/libusb. Texto da licença: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html.",
        ),
        OpenSourceLicense(
            "libuvc", "incluída no UVCAndroid", "BSD-3-Clause",
            "https://github.com/libuvc/libuvc",
            "Copyright dos contribuidores do libuvc (Ken Tossell e outros). Redistribuição permitida sob a " +
                "licença BSD de 3 cláusulas, mantidos o aviso de copyright e a isenção de garantias.",
        ),
        OpenSourceLicense(
            "libyuv", "incluída no UVCAndroid", "BSD-3-Clause",
            "https://chromium.googlesource.com/libyuv/libyuv",
            "Copyright The LibYuv Project Authors. Redistribuição permitida sob a licença BSD de 3 cláusulas, " +
                "mantidos o aviso de copyright e a isenção de garantias.",
        ),
        OpenSourceLicense(
            "NDI SDK", "SDK NewTek/Vizrt (biblioteca nativa libndi)", "Proprietária (NDI SDK License)",
            "https://ndi.video",
            "NDI® is a registered trademark of Vizrt Group. O NDI SDK é software proprietário da NewTek/Vizrt, " +
                "usado sob a licença do SDK; não é software livre e seu código-fonte não é distribuído com o app.",
        ),
        OpenSourceLicense(
            "Netty", "4.1.x (transitiva do Ktor Server)", APACHE,
            "https://netty.io",
            "Copyright The Netty Project. $APACHE_NOTICE",
        ),
        OpenSourceLicense(
            "UVCAndroid", "1.0.8", APACHE,
            "https://github.com/shiyinghan/UVCAndroid",
            "Copyright Shiying Han e contribuidores. $APACHE_NOTICE Inclui libuvc, libusb, libjpeg-turbo e " +
                "libyuv, listados separadamente.",
        ),
    )
}
