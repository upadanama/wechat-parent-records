package com.jz.chatshot.core

/** 微信搜索框位置判断：只按节点垂直中心是否落在屏幕上方 30% 内。 */
object SearchBox {

    fun isSearchBox(centerY: Int, screenHeight: Int): Boolean {
        if (screenHeight <= 0) return false
        return centerY in 0..(screenHeight * 30 / 100)
    }
}
