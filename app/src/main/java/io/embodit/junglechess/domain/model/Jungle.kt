package io.embodit.junglechess.domain.model

/**
 * Jungle Chess (斗兽棋) model. Pure Kotlin, no Android.
 *
 * Board: 7 columns (a..g, left to right as the red player sees it) x 9 rows (1 = far, blue's home row;
 * 9 = near, red's home row). A square is an Int 0..62: `(row - 1) * 7 + col`.
 * Pieces have stable ids 0..15: ids 0..7 are red (rank 1..8 = id + 1), 8..15 blue (rank = id - 7).
 * A [Position] stores `id + 1` per square (0 = empty), so the 3D scene can track every animal.
 */
enum class Side {
    RED, BLUE;

    val other: Side get() = if (this == RED) BLUE else RED
}

enum class Animal(val rank: Int, val letter: Char, val zh: String, val en: String, val asset: String) {
    RAT(1, 'R', "鼠", "rat", "rat"),
    CAT(2, 'C', "猫", "cat", "cat"),
    DOG(3, 'D', "狗", "dog", "dog"),
    WOLF(4, 'W', "狼", "wolf", "wolf"),
    LEOPARD(5, 'P', "豹", "leopard", "leopard"),
    TIGER(6, 'T', "虎", "tiger", "tiger"),
    LION(7, 'L', "狮", "lion", "lion"),
    ELEPHANT(8, 'E', "象", "elephant", "elephant");

    val canSwim: Boolean get() = this == RAT
    val canLeap: Boolean get() = this == LION || this == TIGER

    companion object {
        fun ofRank(rank: Int): Animal = entries[rank - 1]
        fun ofLetter(c: Char): Animal? = entries.firstOrNull { it.letter == c.uppercaseChar() }
    }
}

/** Piece ids 0..15. */
object Pieces {
    const val COUNT = 16
    fun id(side: Side, animal: Animal): Int = (if (side == Side.RED) 0 else 8) + animal.rank - 1
    fun side(id: Int): Side = if (id < 8) Side.RED else Side.BLUE
    fun animal(id: Int): Animal = Animal.ofRank(id % 8 + 1)
    fun ids(side: Side): IntRange = if (side == Side.RED) 0..7 else 8..15
}

object Sq {
    const val COLS = 7
    const val ROWS = 9
    const val COUNT = COLS * ROWS
    fun of(col: Int, row: Int): Int = (row - 1) * COLS + col
    fun col(sq: Int): Int = sq % COLS
    fun row(sq: Int): Int = sq / COLS + 1
    fun valid(col: Int, row: Int): Boolean = col in 0 until COLS && row in 1..ROWS
    fun name(sq: Int): String = "${"abcdefg"[col(sq)]}${row(sq)}"
    fun parse(name: String): Int? {
        if (name.length != 2) return null
        val c = "abcdefg".indexOf(name[0].lowercaseChar())
        val r = name[1].digitToIntOrNull() ?: return null
        return if (Sq.valid(c, r)) of(c, r) else null
    }
}

object Terrain {
    /** b4..c6 and e4..f6. */
    fun isRiver(sq: Int): Boolean {
        val c = Sq.col(sq)
        val r = Sq.row(sq)
        return r in 4..6 && (c in 1..2 || c in 4..5)
    }

    private val redTraps = setOf(Sq.of(2, 9), Sq.of(4, 9), Sq.of(3, 8))
    private val blueTraps = setOf(Sq.of(2, 1), Sq.of(4, 1), Sq.of(3, 2))

    /** The side that owns the trap on [sq] (traps surround that side's den), or null. */
    fun trapOwner(sq: Int): Side? = when (sq) {
        in redTraps -> Side.RED
        in blueTraps -> Side.BLUE
        else -> null
    }

    fun traps(side: Side): Set<Int> = if (side == Side.RED) redTraps else blueTraps

    fun den(side: Side): Int = if (side == Side.RED) Sq.of(3, 9) else Sq.of(3, 1)

    fun denOwner(sq: Int): Side? = when (sq) {
        den(Side.RED) -> Side.RED
        den(Side.BLUE) -> Side.BLUE
        else -> null
    }
}

/** An immutable position: cells hold piece id + 1 (0 = empty) and the side to move. */
class Position(private val cells: IntArray, val toMove: Side) {
    init {
        require(cells.size == Sq.COUNT)
    }

    /** Piece id on [sq], or -1. */
    fun at(sq: Int): Int = cells[sq] - 1

    fun isEmpty(sq: Int): Boolean = cells[sq] == 0

    /** Square of piece [id], or -1 when captured. */
    fun squareOf(id: Int): Int {
        for (s in 0 until Sq.COUNT) if (cells[s] == id + 1) return s
        return -1
    }

    fun pieceIds(side: Side): List<Int> = (0 until Sq.COUNT).mapNotNull { s -> at(s).takeIf { it >= 0 && Pieces.side(it) == side } }

    fun count(side: Side): Int = (0 until Sq.COUNT).count { s -> at(s).let { it >= 0 && Pieces.side(it) == side } }

    fun cellsCopy(): IntArray = cells.copyOf()

    fun with(toMove: Side): Position = Position(cells.copyOf(), toMove)

    /** Compact text: 63 chars ('.' or a letter, upper = red, lower = blue) + ' ' + 'r'/'b'. */
    fun encode(): String {
        val sb = StringBuilder(Sq.COUNT + 2)
        for (s in 0 until Sq.COUNT) {
            val id = at(s)
            sb.append(if (id < 0) '.' else Pieces.animal(id).letter.let { if (Pieces.side(id) == Side.RED) it else it.lowercaseChar() })
        }
        sb.append(' ').append(if (toMove == Side.RED) 'r' else 'b')
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = other is Position && other.toMove == toMove && other.cells.contentEquals(cells)
    override fun hashCode(): Int = cells.contentHashCode() * 31 + toMove.ordinal
    override fun toString(): String = encode()

    companion object {
        fun decode(text: String): Position? {
            val t = text.trim()
            if (t.length != Sq.COUNT + 2 || t[Sq.COUNT] != ' ') return null
            val cells = IntArray(Sq.COUNT)
            val seen = HashSet<Int>()
            for (s in 0 until Sq.COUNT) {
                val ch = t[s]
                if (ch == '.') continue
                val animal = Animal.ofLetter(ch) ?: return null
                val side = if (ch.isUpperCase()) Side.RED else Side.BLUE
                val id = Pieces.id(side, animal)
                if (!seen.add(id)) return null
                cells[s] = id + 1
            }
            val side = when (t[Sq.COUNT + 1]) { 'r' -> Side.RED; 'b' -> Side.BLUE; else -> return null }
            return Position(cells, side)
        }

        /** Standard opening (design package gameSpec.pieces). */
        fun start(): Position {
            val cells = IntArray(Sq.COUNT)
            fun put(side: Side, animal: Animal, sq: String) { cells[Sq.parse(sq)!!] = Pieces.id(side, animal) + 1 }
            put(Side.RED, Animal.TIGER, "a9"); put(Side.RED, Animal.LION, "g9")
            put(Side.RED, Animal.CAT, "b8"); put(Side.RED, Animal.DOG, "f8")
            put(Side.RED, Animal.ELEPHANT, "a7"); put(Side.RED, Animal.WOLF, "c7")
            put(Side.RED, Animal.LEOPARD, "e7"); put(Side.RED, Animal.RAT, "g7")
            put(Side.BLUE, Animal.LION, "a1"); put(Side.BLUE, Animal.TIGER, "g1")
            put(Side.BLUE, Animal.DOG, "b2"); put(Side.BLUE, Animal.CAT, "f2")
            put(Side.BLUE, Animal.RAT, "a3"); put(Side.BLUE, Animal.LEOPARD, "c3")
            put(Side.BLUE, Animal.WOLF, "e3"); put(Side.BLUE, Animal.ELEPHANT, "g3")
            return Position(cells, Side.RED)
        }

        fun of(cells: IntArray, toMove: Side) = Position(cells.copyOf(), toMove)
    }
}

/** A move between two squares; [captured] is the piece id taken, or -1. */
data class Move(val from: Int, val to: Int, val piece: Int, val captured: Int = -1) {
    val isJump: Boolean get() {
        val dc = kotlin.math.abs(Sq.col(to) - Sq.col(from))
        val dr = kotlin.math.abs(Sq.row(to) - Sq.row(from))
        return dc + dr > 1
    }

    /** JCN-like notation: "R g7-g6", "L a9xa6". */
    fun notation(): String = "${Pieces.animal(piece).letter} ${Sq.name(from)}${if (captured >= 0) "x" else "-"}${Sq.name(to)}"
}

enum class WinReason { DEN, ALL_CAPTURED, NO_MOVES, RESIGN, REPETITION }

sealed interface GameStatus {
    data object Ongoing : GameStatus
    data class Won(val winner: Side, val reason: WinReason) : GameStatus
}
