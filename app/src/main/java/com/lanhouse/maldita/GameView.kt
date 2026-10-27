package com.lanhouse.maldita

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

const val VW = 320f
const val VH = 180f

enum class State { MENU, CLASS_SELECT, PLAYING }

enum class HeroClass(
    val label: String,
    val desc: String,
    val color: Int,
    val maxHp: Int,
    val speed: Float,
    val damage: Int,
    val ranged: Boolean,
    val cooldown: Float
) {
    ORFAO("Órfão Corajoso", "Guerreiro\nEspada corpo a corpo\nMais vida", 0xFFE0A030.toInt(), 120, 70f, 14, false, 0.35f),
    CACADOR("Caçador de Bugs", "Arqueiro\nFlechas rápidas\nMais veloz", 0xFF50C878.toInt(), 90, 85f, 9, true, 0.30f),
    FEITICEIRO("Feiticeiro do Firewall", "Mago\nBola de fogo forte\nDisparo lento", 0xFFE04040.toInt(), 80, 75f, 18, true, 0.60f)
}

class Enemy(var x: Float, var y: Float) {
    var hp = 30
    var hitFlash = 0f
}

class Shot(var x: Float, var y: Float, val vx: Float, val vy: Float, val dmg: Int, val color: Int) {
    var life = 1.2f
}

class GameView(context: Context) : SurfaceView(context), Runnable {

    private var thread: Thread? = null
    @Volatile private var running = false

    private val paint = Paint().apply {
        isAntiAlias = false
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    @Volatile private var state = State.MENU
    @Volatile private var pendingStart: HeroClass? = null

    // tela virtual -> tela real
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    // jogador
    private var hero = HeroClass.ORFAO
    private var px = VW / 2
    private var py = VH / 2
    private var fx = 1f
    private var fy = 0f
    private var hp = 100
    private var xp = 0
    private var level = 1
    private var cooldown = 0f
    private var swing = 0f
    private var hurtTimer = 0f

    private val enemies = mutableListOf<Enemy>()
    private val shots = mutableListOf<Shot>()
    private var spawnTimer = 0f

    // controles
    private var joyId = -1
    private var joyCx = 0f
    private var joyCy = 0f
    @Volatile private var joyDx = 0f
    @Volatile private var joyDy = 0f
    private var attackId = -1
    @Volatile private var attackHeld = false

    fun resume() {
        if (running) return
        running = true
        thread = Thread(this).also { it.start() }
    }

    fun pause() {
        running = false
        try {
            thread?.join()
        } catch (_: InterruptedException) {
        }
        thread = null
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            if (!holder.surface.isValid) {
                try { Thread.sleep(16) } catch (_: InterruptedException) {}
                last = System.nanoTime()
                continue
            }
            val now = System.nanoTime()
            val dt = min((now - last) / 1_000_000_000f, 0.05f)
            last = now
            update(dt)
            val c = holder.lockCanvas() ?: continue
            try {
                render(c)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
            try { Thread.sleep(8) } catch (_: InterruptedException) {}
        }
    }

    // ---------------------------------------------------------------- lógica

    private fun maxHp() = hero.maxHp + (level - 1) * 10
    private fun damage() = hero.damage + level * 2

    private fun startGame(h: HeroClass) {
        hero = h
        px = VW / 2
        py = VH / 2
        fx = 1f
        fy = 0f
        level = 1
        xp = 0
        hp = maxHp()
        cooldown = 0f
        swing = 0f
        hurtTimer = 0f
        spawnTimer = 0f
        enemies.clear()
        shots.clear()
        state = State.PLAYING
    }

    private fun gainXp(amount: Int) {
        xp += amount
        while (xp >= level * 30) {
            xp -= level * 30
            level++
            hp = min(maxHp(), hp + 20)
        }
    }

    private fun hit(e: Enemy, dmg: Int) {
        e.hp -= dmg
        e.hitFlash = 0.1f
    }

    private fun attack() {
        cooldown = hero.cooldown
        if (hero.ranged) {
            val spd = if (hero == HeroClass.CACADOR) 180f else 130f
            shots.add(Shot(px, py, fx * spd, fy * spd, damage(), hero.color))
        } else {
            swing = 0.15f
            for (e in enemies) {
                val dx = e.x - px
                val dy = e.y - py
                if (hypot(dx, dy) < 26f && (dx * fx + dy * fy) > -4f) hit(e, damage())
            }
        }
    }

    private fun update(dt: Float) {
        pendingStart?.let {
            startGame(it)
            pendingStart = null
        }
        if (state != State.PLAYING) return

        // movimento
        val jx = joyDx
        val jy = joyDy
        val mag = hypot(jx, jy)
        if (mag > 0.15f) {
            val nx = jx / mag
            val ny = jy / mag
            px += nx * hero.speed * dt
            py += ny * hero.speed * dt
            fx = nx
            fy = ny
        }
        px = px.coerceIn(8f, VW - 8f)
        py = py.coerceIn(20f, VH - 8f)

        // ataque
        cooldown -= dt
        swing -= dt
        if (attackHeld && cooldown <= 0f) attack()

        // tiros
        val iter = shots.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            s.x += s.vx * dt
            s.y += s.vy * dt
            s.life -= dt
            var gone = s.life <= 0f || s.x < 0f || s.x > VW || s.y < 0f || s.y > VH
            if (!gone) {
                for (e in enemies) {
                    if (hypot(e.x - s.x, e.y - s.y) < 8f) {
                        hit(e, s.dmg)
                        gone = true
                        break
                    }
                }
            }
            if (gone) iter.remove()
        }

        // inimigos (por enquanto só o "Mouse")
        hurtTimer -= dt
        for (e in enemies) {
            val dx = px - e.x
            val dy = py - e.y
            val d = max(hypot(dx, dy), 0.01f)
            e.x += dx / d * 28f * dt
            e.y += dy / d * 28f * dt
            e.hitFlash -= dt
            if (d < 9f && hurtTimer <= 0f) {
                hp -= 6
                hurtTimer = 0.6f
            }
        }
        val dead = enemies.filter { it.hp <= 0 }
        for (e in dead) gainXp(10)
        enemies.removeAll(dead.toSet())

        // spawn
        spawnTimer -= dt
        if (spawnTimer <= 0f && enemies.size < 6) {
            spawnTimer = 2f
            val sx: Float
            val sy: Float
            when (Random.nextInt(4)) {
                0 -> { sx = -5f; sy = Random.nextFloat() * VH }
                1 -> { sx = VW + 5f; sy = Random.nextFloat() * VH }
                2 -> { sx = Random.nextFloat() * VW; sy = -5f }
                else -> { sx = Random.nextFloat() * VW; sy = VH + 5f }
            }
            enemies.add(Enemy(sx, sy))
        }

        if (hp <= 0) {
            attackHeld = false
            state = State.CLASS_SELECT
        }
    }

    // ---------------------------------------------------------------- toque

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                onDown(id, (e.getX(idx) - offX) / scale, (e.getY(idx) - offY) / scale)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    if (e.getPointerId(i) == joyId) {
                        updateJoy((e.getX(i) - offX) / scale, (e.getY(i) - offY) / scale)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onUp(id)
            MotionEvent.ACTION_CANCEL -> {
                joyId = -1; joyDx = 0f; joyDy = 0f
                attackId = -1; attackHeld = false
            }
        }
        return true
    }

    private fun onDown(id: Int, x: Float, y: Float) {
        when (state) {
            State.MENU -> state = State.CLASS_SELECT
            State.CLASS_SELECT -> {
                for ((i, hc) in HeroClass.values().withIndex()) {
                    val bx = 15f + i * 100f
                    if (x in bx..(bx + 90f) && y in 30f..150f) pendingStart = hc
                }
            }
            State.PLAYING -> {
                if (x < VW / 2) {
                    if (joyId == -1) {
                        joyId = id
                        joyCx = x
                        joyCy = y
                        joyDx = 0f
                        joyDy = 0f
                    }
                } else if (attackId == -1) {
                    attackId = id
                    attackHeld = true
                }
            }
        }
    }

    private fun updateJoy(x: Float, y: Float) {
        joyDx = ((x - joyCx) / 22f).coerceIn(-1f, 1f)
        joyDy = ((y - joyCy) / 22f).coerceIn(-1f, 1f)
    }

    private fun onUp(id: Int) {
        if (id == joyId) {
            joyId = -1
            joyDx = 0f
            joyDy = 0f
        }
        if (id == attackId) {
            attackId = -1
            attackHeld = false
        }
    }

    // ---------------------------------------------------------------- desenho

    private fun rect(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawRect(x, y, x + w, y + h, paint)
    }

    private fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawCircle(x, y, r, paint)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, center: Boolean = true) {
        paint.color = color
        paint.style = Paint.Style.FILL
        paint.textSize = size
        paint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(s, x, y, paint)
    }

    private fun drawHero(c: Canvas, x: Float, y: Float, color: Int, k: Float) {
        rect(c, x - 5 * k, y - 2 * k, 10 * k, 10 * k, color)
        rect(c, x - 4 * k, y - 9 * k, 8 * k, 7 * k, 0xFFF2C9A0.toInt())
        rect(c, x - 3 * k, y - 7 * k, 2 * k, 2 * k, Color.BLACK)
        rect(c, x + 1 * k, y - 7 * k, 2 * k, 2 * k, Color.BLACK)
        rect(c, x - 4 * k, y - 10 * k, 8 * k, 3 * k, color)
    }

    private fun render(c: Canvas) {
        c.drawColor(Color.BLACK)
        val s = min(c.width / VW, c.height / VH)
        scale = s
        offX = (c.width - VW * s) / 2f
        offY = (c.height - VH * s) / 2f
        c.save()
        c.translate(offX, offY)
        c.scale(s, s)
        c.clipRect(0f, 0f, VW, VH)
        when (state) {
            State.MENU -> drawMenu(c)
            State.CLASS_SELECT -> drawSelect(c)
            State.PLAYING -> drawGame(c)
        }
        c.restore()
    }

    private fun drawMenu(c: Canvas) {
        rect(c, 0f, 0f, VW, VH, 0xFF140A24.toInt())
        text(c, "LAN HOUSE", VW / 2, 70f, 30f, 0xFFE03030.toInt())
        text(c, "MALDITA", VW / 2, 102f, 24f, 0xFF8A2BE2.toInt())
        if ((System.currentTimeMillis() / 500) % 2L == 0L) {
            text(c, "TOQUE PARA COMEÇAR", VW / 2, 150f, 10f, Color.WHITE)
        }
    }

    private fun drawSelect(c: Canvas) {
        rect(c, 0f, 0f, VW, VH, 0xFF140A24.toInt())
        text(c, "ESCOLHA SUA CLASSE", VW / 2, 20f, 11f, Color.WHITE)
        for ((i, hc) in HeroClass.values().withIndex()) {
            val bx = 15f + i * 100f
            rect(c, bx, 30f, 90f, 120f, 0xFF8A7BB0.toInt())
            rect(c, bx + 2f, 32f, 86f, 116f, 0xFF241838.toInt())
            drawHero(c, bx + 45f, 70f, hc.color, 2f)
            text(c, hc.label, bx + 45f, 106f, 6.5f, Color.WHITE)
            for ((j, line) in hc.desc.split("\n").withIndex()) {
                val col = if (j == 0) 0xFFFFD060.toInt() else 0xFFBBBBBB.toInt()
                text(c, line, bx + 45f, 120f + j * 9f, 6f, col)
            }
        }
    }

    private fun drawGame(c: Canvas) {
        // chão
        for (ty in 0 until 12) {
            for (tx in 0 until 20) {
                val col = if ((tx + ty) % 2 == 0) 0xFF2A2438.toInt() else 0xFF302A42.toInt()
                rect(c, tx * 16f, ty * 16f, 16f, 16f, col)
            }
        }

        // inimigos: Mouse
        for (e in enemies) {
            val col = if (e.hitFlash > 0f) Color.WHITE else 0xFF9AA0A6.toInt()
            rect(c, e.x - 5f, e.y - 3f, 10f, 7f, col)
            rect(c, e.x - 3f, e.y - 2f, 2f, 2f, 0xFFFF2020.toInt())
            rect(c, e.x + 1f, e.y - 2f, 2f, 2f, 0xFFFF2020.toInt())
            rect(c, e.x - 1f, e.y + 4f, 2f, 4f, col)
        }

        // tiros
        for (s in shots) rect(c, s.x - 1.5f, s.y - 1.5f, 3f, 3f, s.color)

        // golpe de espada
        if (swing > 0f) {
            rect(c, px + fx * 14f - 6f, py + fy * 14f - 6f, 12f, 12f, 0xAAFFFFFF.toInt())
        }

        // herói (pisca ao levar dano)
        if (hurtTimer <= 0f || ((hurtTimer * 20f).toInt() % 2 == 0)) {
            drawHero(c, px, py, hero.color, 1f)
        }

        // HUD
        rect(c, 5f, 5f, 82f, 8f, Color.BLACK)
        rect(c, 6f, 6f, 80f * hp.coerceAtLeast(0) / maxHp(), 6f, 0xFFD02030.toInt())
        rect(c, 5f, 14f, 82f, 5f, Color.BLACK)
        rect(c, 6f, 15f, 80f * xp / (level * 30), 3f, 0xFF30B0F0.toInt())
        text(c, "NV $level", 92f, 12f, 8f, Color.WHITE, false)

        // controles
        if (joyId == -1) {
            circle(c, 45f, 135f, 18f, 0x22FFFFFF.toInt())
        } else {
            circle(c, joyCx, joyCy, 20f, 0x33FFFFFF.toInt())
            circle(c, joyCx + joyDx * 14f, joyCy + joyDy * 14f, 9f, 0x66FFFFFF.toInt())
        }
        circle(c, 275f, 135f, 22f, if (attackHeld) 0x66FF4040.toInt() else 0x33FFFFFF.toInt())
        text(c, "ATK", 275f, 138f, 9f, 0xAAFFFFFF.toInt())
    }
}
