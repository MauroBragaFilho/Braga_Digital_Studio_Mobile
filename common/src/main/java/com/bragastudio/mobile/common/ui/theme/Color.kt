package com.bragastudio.mobile.common.ui.theme

import androidx.compose.ui.graphics.Color

// === Paleta Principal (Dark / Profissional) ===

/** Vermelho/rosa da marca - cor de destaque (primary) */
val BdsmAccent = Color(0xFFE6003E)

/** Azul vibrante - ações primárias e destaques */
val BdsmBlue = Color(0xFF2979FF)

/** Cyan profissional - ações secundárias */
val BdsmCyan = Color(0xFF00E5FF)

/** Vermelho de gravação */
val BdsmRed = Color(0xFFFF1744)

/** Verde de status / online */
val BdsmGreen = Color(0xFF00E676)

/** Amarelo de alerta */
val BdsmAmber = Color(0xFFFFD600)

// === Superfícies ===

/** Fundo principal da aplicação (preto puro, OLED) */
val BdsmBackground = Color(0xFF000000)

/** Superfície elevada (cards, painéis) */
val BdsmSurface = Color(0xFF121212)

/** Superfície elevada nível 2 (menus, modais) */
val BdsmSurfaceVariant = Color(0xFF1C1C1C)

/** Borda sutil para separação de elementos */
val BdsmOutline = Color(0xFF2A2A2A)

// === Texto ===

val BdsmOnBackground = Color(0xFFE6E8EB)
val BdsmOnSurface = Color(0xFFE6E8EB)
val BdsmOnSurfaceVariant = Color(0xFFA8A8A8)

// === Legado (mantido para compatibilidade) ===
val PrimaryDark = BdsmBlue
val SecondaryDark = BdsmCyan
val BackgroundDark = BdsmBackground
val SurfaceDark = BdsmSurface
val ErrorDark = BdsmRed
val OnPrimaryDark = Color.White
val OnSecondaryDark = Color.Black
val OnBackgroundDark = BdsmOnBackground
val OnSurfaceDark = BdsmOnSurface
val OnErrorDark = Color.White
