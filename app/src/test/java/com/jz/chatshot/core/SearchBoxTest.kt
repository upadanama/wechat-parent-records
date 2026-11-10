package com.jz.chatshot.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBoxTest {

    @Test
    fun topAreaIsSearchBox() {
        assertTrue(SearchBox.isSearchBox(200, 2400))
    }

    @Test
    fun thirtyPercentBoundaryIsSearchBox() {
        assertTrue(SearchBox.isSearchBox(720, 2400))
    }

    @Test
    fun bottomChatInputIsNotSearchBox() {
        assertFalse(SearchBox.isSearchBox(2000, 2400))
    }
}
