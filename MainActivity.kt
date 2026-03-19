package com.example.beirus

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    init { System.loadLibrary("beirus_engine") }

    // ── Views ─────────────────────────────────────────────────────────────
    private lateinit var waveformView: WaveformView
    private val padButtons = arrayOfNulls<Button>(16)

    // ── State ─────────────────────────────────────────────────────────────
    private var chops   = intArrayOf()
    private var samples = floatArrayOf()
    private var isRecording = false

    // ── JNI ───────────────────────────────────────────────────────────────
    private external fun nativeStartEngine(): Boolean
    private external fun nativeStopEngine()
    private external fun nativeLoadSample(data: FloatArray)
    private external fun nativeTrigger(frame: Int)
    private external fun nativeAutoChop(num: Int)
    private external fun nativeGetChops(): IntArray
    private external fun nativeSetPitch(inc: Float)
    private external fun nativeSetCutoff(c: Float)
    private external fun nativeToggleRecord(rec: Boolean)
    private external fun nativeGetRecordedData(): FloatArray

    // ── File picker ───────────────────────────────────────────────────────
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) processWav(uri)
        }

    // ─────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        waveformView = findViewById(R.id.waveformView)

        if (!nativeStartEngine()) {
            toast("Audio engine failed to start (Oboe)")
        }

        // Load button
        findViewById<Button>(R.id.btnLoad).setOnClickListener {
            pickFile.launch("audio/*")
        }

        // Record button
        findViewById<Button>(R.id.btnRecord).setOnClickListener {
            handleRecordToggle()
        }

        // Pitch slider (25 % – 400 %)
        findViewById<SeekBar>(R.id.pitchSlider).setOnSeekBarChangeListener(
            seekListener { progress ->
                nativeSetPitch(progress.coerceIn(25, 400) / 100f)
            }
        )

        // Filter slider (0 % – 100 %)
        findViewById<SeekBar>(R.id.filterSlider).setOnSeekBarChangeListener(
            seekListener { progress ->
                nativeSetCutoff(progress.coerceIn(0, 100) / 100f)
            }
        )

        // 16 pad buttons
        val padGrid = findViewById<GridLayout>(R.id.padGrid)
        for (i in 0 until 16) {
            val btn = padGrid.getChildAt(i) as? Button ?: continue
            padButtons[i] = btn
            val padIndex = i
            btn.setOnClickListener { triggerPad(padIndex) }
        }
    }

    override fun onDestroy() {
        nativeStopEngine()
        super.onDestroy()
    }

    // ── Pad triggering ────────────────────────────────────────────────────
    private fun triggerPad(index: Int) {
        if (index >= chops.size || chops.isEmpty()) return
        nativeTrigger(chops[index])
        waveformView.setActivePad(index)
        padButtons[index]?.isSelected = true
        // Reset selection highlight after ~300 ms
        waveformView.postDelayed({
            padButtons[index]?.isSelected = false
        }, 300)
    }

    // ── WAV loading ───────────────────────────────────────────────────────
    private fun processWav(uri: Uri) {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.size < 44) { toast("Could not read file"); return }

        val decoded = decodeWav16BitPcmToFloatMono(bytes)
        if (decoded == null || decoded.isEmpty()) {
            toast("Only 16-bit PCM WAV is supported right now")
            return
        }

        samples = decoded
        waveformView.updateWaveform(samples)

        nativeLoadSample(samples)
        nativeAutoChop(16)            // ← now uses real transient detection
        chops = nativeGetChops()
        waveformView.updateChops(chops, samples.size)

        // Label pad buttons with their pad number
        for (i in padButtons.indices) {
            padButtons[i]?.text = "P${i + 1}"
            padButtons[i]?.isEnabled = i < chops.size
        }

        toast("Loaded • ${chops.size} transient pads detected")
    }

    // ── Minimal WAV parser (PCM-16, mono or stereo → float mono) ─────────
    private fun decodeWav16BitPcmToFloatMono(bytes: ByteArray): FloatArray? {
        fun u32le(off: Int) =
            ByteBuffer.wrap(bytes, off, 4).order(ByteOrder.LITTLE_ENDIAN).int
        fun u16le(off: Int) =
            ByteBuffer.wrap(bytes, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

        if (String(bytes, 0, 4) != "RIFF") return null
        if (String(bytes, 8, 4) != "WAVE") return null

        var offset = 12
        var audioFormat = 0; var numChannels = 0
        var sampleRate = 44100; var bitsPerSample = 0
        var dataOffset = -1; var dataSize = -1

        while (offset + 8 <= bytes.size) {
            val chunkId   = String(bytes, offset, 4)
            val chunkSize = u32le(offset + 4)
            val chunkData = offset + 8
            if (chunkData + chunkSize > bytes.size) break
            when (chunkId) {
                "fmt " -> {
                    audioFormat  = u16le(chunkData)
                    numChannels  = u16le(chunkData + 2)
                    sampleRate   = u32le(chunkData + 4)
                    bitsPerSample= u16le(chunkData + 14)
                }
                "data" -> { dataOffset = chunkData; dataSize = chunkSize; break }
            }
            offset = chunkData + chunkSize + (chunkSize % 2)
        }

        if (audioFormat != 1 || bitsPerSample != 16) return null
        if (dataOffset < 0 || dataSize <= 0)         return null
        if (numChannels !in 1..2)                    return null

        val frameCount = dataSize / (2 * numChannels)
        val out = FloatArray(frameCount)
        var p = dataOffset
        for (i in 0 until frameCount) {
            var sum = 0
            repeat(numChannels) {
                sum += ByteBuffer.wrap(bytes, p, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                p += 2
            }
            out[i] = (sum / numChannels / 32768f).coerceIn(-1f, 1f)
        }
        toast("WAV ${sampleRate} Hz  ${numChannels}ch  ${frameCount} frames")
        return out
    }

    // ── Recording ─────────────────────────────────────────────────────────
    private fun handleRecordToggle() {
        isRecording = !isRecording
        nativeToggleRecord(isRecording)
        val btn = findViewById<Button>(R.id.btnRecord)
        if (isRecording) {
            btn.text = "STOP REC ●"
            toast("Recording…")
        } else {
            btn.text = "REC"
            val data = nativeGetRecordedData()
            if (data.isEmpty()) { toast("No audio recorded"); return }
            val file = saveFloatMonoAsWav(data, 44100)
            toast("Saved: ${file?.name ?: "(failed)"}")
        }
    }

    private fun saveFloatMonoAsWav(data: FloatArray, sampleRate: Int): File? = runCatching {
        val dir = if (Build.VERSION.SDK_INT >= 29)
            getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        else
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val outDir = File(dir, "Beirus").apply { mkdirs() }
        val file   = File(outDir, "Beirus_${System.currentTimeMillis()}.wav")

        val pcm = ShortArray(data.size) { i ->
            (data[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
        }
        val baos = ByteArrayOutputStream()
        val sub2 = pcm.size * 2
        fun ws(s: String) = baos.write(s.toByteArray(Charsets.US_ASCII))
        fun wi(v: Int)  = baos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun wsh(v: Short)= baos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array())
        ws("RIFF"); wi(36 + sub2); ws("WAVE")
        ws("fmt "); wi(16); wsh(1); wsh(1); wi(sampleRate)
        wi(sampleRate * 2); wsh(2); wsh(16)
        ws("data"); wi(sub2)
        val bb = ByteBuffer.allocate(sub2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bb.putShort(it) }
        baos.write(bb.array())
        FileOutputStream(file).use { it.write(baos.toByteArray()) }
        file
    }.getOrNull()

    // ── Keyboard (USB OTG) ────────────────────────────────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val index = when (keyCode) {
            KeyEvent.KEYCODE_Z -> 0;  KeyEvent.KEYCODE_X -> 1
            KeyEvent.KEYCODE_C -> 2;  KeyEvent.KEYCODE_V -> 3
            KeyEvent.KEYCODE_A -> 4;  KeyEvent.KEYCODE_S -> 5
            KeyEvent.KEYCODE_D -> 6;  KeyEvent.KEYCODE_F -> 7
            else -> -1
        }
        if (index != -1 && index < chops.size) { triggerPad(index); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun seekListener(onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) = onChange(p)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
}
