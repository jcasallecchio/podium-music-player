package app.podium.core.designsystem.type

import kotlin.test.Test
import kotlin.test.assertEquals

class PodiumLanguageTest {
    @Test
    fun `track context actions are translated while keeping the artist name`() {
        assertEquals("Adicionar aos favoritos", podiumText("Add to favorites", PodiumLanguage.PORTUGUESE_BRAZIL))
        assertEquals("Ir para o álbum", podiumText("Go to album", PodiumLanguage.PORTUGUESE_BRAZIL))
        assertEquals("Ir para Akon", podiumText("Go to Akon", PodiumLanguage.PORTUGUESE_BRAZIL))
        assertEquals("Limpar fila", podiumText("Clear Up Next", PodiumLanguage.PORTUGUESE_BRAZIL))
    }

    @Test
    fun `update and about labels have Brazilian Portuguese names`() {
        assertEquals("Atualização", podiumText("Software update", PodiumLanguage.PORTUGUESE_BRAZIL))
        assertEquals("Sobre", podiumText("About", PodiumLanguage.PORTUGUESE_BRAZIL))
    }
}
