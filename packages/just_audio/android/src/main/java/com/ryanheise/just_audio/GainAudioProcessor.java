package com.ryanheise.just_audio;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import java.nio.ByteBuffer;

/**
 * Absorb patch: the EQ loudness slider as plain sample gain inside the
 * ExoPlayer audio sink, the way the iOS tap applies it. Android's
 * LoudnessEnhancer is a compressor that leaves narration already peaking near
 * full scale almost untouched, and it lives on the audio session, so it dies
 * with the activity and on phones whose effect stack is broken. This runs on
 * every sample regardless. Peaks are soft-clipped so a big boost distorts
 * gently instead of wrapping.
 */
public final class GainAudioProcessor extends BaseAudioProcessor {
    private static volatile float sGain = 1f;

    /** Gain in millibels (100 mB = 1 dB); zero or less is unity. */
    public static void setGainMb(int gainMb) {
        sGain = gainMb <= 0 ? 1f : (float) Math.pow(10.0, gainMb / 2000.0);
    }

    @Override
    public AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        int encoding = inputAudioFormat.encoding;
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;
        float gain = sGain;
        ByteBuffer output = replaceOutputBuffer(remaining);
        if (gain == 1f) {
            output.put(inputBuffer);
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= 2) {
                float sample = softClip(inputBuffer.getShort() * gain / 32768f);
                output.putShort((short) Math.round(sample * 32767f));
            }
        } else {
            while (inputBuffer.remaining() >= 4) {
                output.putFloat(softClip(inputBuffer.getFloat() * gain));
            }
        }
        output.flip();
    }

    // Linear up to 0.8, then eases into the ceiling. Continuous with unit
    // slope at the knee, never reaches 1.0.
    private static float softClip(float x) {
        float magnitude = Math.abs(x);
        if (magnitude <= 0.8f) return x;
        float y = 0.8f + 0.2f * (float) Math.tanh((magnitude - 0.8f) / 0.2f);
        return x < 0 ? -y : y;
    }
}
