package com.abhayshah.volumecontrol;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;

/** One shared-output effect. Android vendors may bypass this on some output paths. */
final class GainEngine implements AutoCloseable {
    private DynamicsProcessing effect;
    private final Runnable lost;
    GainEngine(int session, Runnable lost) {
        this.lost = lost;
        try {
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    false, 0, false, 0, false, 0, false).build();
            config.setInputGainAllChannelsTo(-60f);
            effect = new DynamicsProcessing(0, session, config);
            effect.setControlStatusListener((e, control) -> { if (!control) this.lost.run(); });
            if (!effect.hasControl()) throw new IllegalStateException("Another audio effect has control");
        } catch (RuntimeException failure) { close(); throw failure; }
    }
    void apply(int level) {
        if (effect == null || !effect.hasControl()) throw new IllegalStateException("Audio effect unavailable");
        effect.setInputGainAllChannelsTo(Level.decibels(level));
        if (effect.setEnabled(true) != AudioEffect.SUCCESS || !effect.getEnabled()) {
            throw new IllegalStateException("Audio output rejected attenuation");
        }
    }
    float gain() { return effect.getInputGainByChannelIndex(0); }
    @Override public void close() {
        if (effect != null) {
            try { effect.setControlStatusListener(null); effect.release(); }
            catch (RuntimeException ignored) { /* Server may already be gone. */ }
            effect = null;
        }
    }
}
