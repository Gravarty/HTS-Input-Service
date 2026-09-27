package com.gravarty.htsp.core

enum class HtsType(val id: Int) {
    MAP(1),
    S64(2),
    STR(3),
    BIN(4),
    LIST(5),
    FLOAT(6),
    BOOL(7),
    UUID(8);

    companion object {
        fun fromId(id: Int): HtsType? = entries.find { it.id == id }
    }
}
