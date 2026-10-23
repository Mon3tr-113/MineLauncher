package mclauncher.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 背景音乐播放器（带歌单、进度、软件音量）。
 *
 * <p>MP3 用 jlayer 自行解码后写 {@link SourceDataLine}，WAV 走 {@link Clip}。
 * 音量是「软件增益」：逐样本乘系数，声卡不支持 MASTER_GAIN 也能放大到 200%。
 */
public final class MusicPlayer {

    private static final List<String> playlist = new CopyOnWriteArrayList<>();
    private static volatile int index = -1;

    private static volatile boolean running;
    private static volatile Thread thread;
    private static volatile SourceDataLine line;
    private static volatile Clip clip;

    private static volatile int volume = 100;

    /** 已播放样本数 / 采样率，用于算进度。 */
    private static volatile long elapsedSamples;
    private static volatile long totalSamples;
    private static volatile int sampleRate = 44100;

    private MusicPlayer() {
    }

    // ---------------------------------------------------------------- 音量

    public static void setVolume(int percent) {
        volume = Math.max(0, Math.min(200, percent));
        applyClipGain();
    }

    public static int getVolume() {
        return volume;
    }

    // ---------------------------------------------------------------- 歌单

    /** 设置歌单并从 startIndex 开始播放。 */
    public static synchronized void setPlaylist(List<String> tracks, int startIndex) {
        stopInternal();
        playlist.clear();
        for (String t : tracks) {
            if (t != null && !t.isBlank()) playlist.add(t);
        }
        if (playlist.isEmpty()) {
            index = -1;
            return;
        }
        index = Math.max(0, Math.min(startIndex, playlist.size() - 1));
        start();
    }

    public static List<String> getPlaylist() {
        return new ArrayList<>(playlist);
    }

    /** 单曲播放：等价于把歌单换成这一首。 */
    public static synchronized void play(String path) {
        if (path == null || path.isBlank()) {
            stop();
            return;
        }
        setPlaylist(List.of(path), 0);
    }

    public static String currentTrack() {
        int i = index;
        return (i >= 0 && i < playlist.size()) ? playlist.get(i) : null;
    }

    /** 当前曲目的显示名（去掉目录和扩展名）。 */
    public static String currentName() {
        String t = currentTrack();
        if (t == null) return "未播放";
        String name = new File(t).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static int currentIndex() {
        return index;
    }

    public static synchronized void next() {
        if (playlist.isEmpty()) return;
        index = (index + 1) % playlist.size();
        restartCurrent();
    }

    public static synchronized void previous() {
        if (playlist.isEmpty()) return;
        index = (index - 1 + playlist.size()) % playlist.size();
        restartCurrent();
    }

    // ---------------------------------------------------------------- 进度

    public static boolean isPlaying() {
        return running;
    }

    public static long elapsedMs() {
        return elapsedSamples * 1000L / Math.max(1, sampleRate);
    }

    public static long totalMs() {
        return totalSamples * 1000L / Math.max(1, sampleRate);
    }

    /** 0~1 的播放进度，未知时返回 0。 */
    public static double progress() {
        long t = totalSamples;
        if (t <= 0) return 0;
        double p = (double) elapsedSamples / t;
        return Math.max(0, Math.min(1, p));
    }

    public static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        return String.format("%d:%02d", total / 60, total % 60);
    }

    // ---------------------------------------------------------------- 播放

    private static void start() {
        running = true;
        thread = new Thread(MusicPlayer::loop, "music-player");
        thread.setDaemon(true);
        thread.start();
    }

    private static synchronized void restartCurrent() {
        stopInternal();
        if (playlist.isEmpty()) return;
        start();
    }

    private static void loop() {
        while (running) {
            String track = currentTrack();
            if (track == null) break;
            File f = new File(track);
            if (!f.isFile()) {
                index++;
                if (index >= playlist.size()) index = 0;
                if (playlist.size() == 1) break;
                continue;
            }
            try {
                if (track.toLowerCase().endsWith(".mp3")) {
                    decodeMp3(f);
                } else {
                    playClipBlocking(f);
                }
            } catch (Exception ignored) {
                // 单曲失败就跳过
            } finally {
                closeLine();
            }
            if (!running) break;
            // 一首播完，下一首
            index++;
            if (index >= playlist.size()) index = 0;
            if (playlist.size() == 1) {
                // 单曲循环
                elapsedSamples = 0;
            }
        }
        running = false;
    }

    /** 解码整个 MP3 写入声卡；播完返回。 */
    private static void decodeMp3(File f) throws Exception {
        Decoder decoder = new Decoder();
        elapsedSamples = 0;
        totalSamples = estimateTotalSamples(f);
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
            Bitstream bitstream = new Bitstream(in);
            try {
                while (running) {
                    Header header = bitstream.readFrame();
                    if (header == null) break;
                    SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                    if (line == null) {
                        sampleRate = header.frequency();
                        AudioFormat fmt = new AudioFormat(sampleRate, 16,
                                header.mode() == Header.SINGLE_CHANNEL ? 1 : 2, true, false);
                        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
                        line = (SourceDataLine) AudioSystem.getLine(info);
                        line.open(fmt);
                        line.start();
                    }

                    short[] buf = samples.getBuffer();
                    int len = samples.getBufferLength();
                    scale(buf, len);
                    line.write(toBytes(buf, len), 0, len * 2);
                    elapsedSamples += len;
                    bitstream.closeFrame();
                }
                if (line != null) line.drain();
            } finally {
                try {
                    bitstream.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 用「文件大小 / 首帧码率」估算总采样数（VBR 会有偏差，够进度条用）。 */
    private static long estimateTotalSamples(File f) {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
            Bitstream bs = new Bitstream(in);
            Header h = bs.readFrame();
            if (h == null) return 0;
            int rate = h.frequency();
            int bitrate = h.bitrate();
            bs.closeFrame();
            bs.close();
            if (bitrate <= 0) return 0;
            double seconds = (double) f.length() * 8.0 / bitrate;
            return (long) (seconds * rate);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void scale(short[] buf, int len) {
        float g = volume / 100f;
        if (g == 1f || buf == null) return;
        for (int i = 0; i < len && i < buf.length; i++) {
            int v = Math.round(buf[i] * g);
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            buf[i] = (short) v;
        }
    }

    private static byte[] toBytes(short[] buf, int len) {
        byte[] out = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            short s = buf[i];
            out[i * 2] = (byte) (s & 0xff);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        return out;
    }

    /** WAV：用 Clip 循环播放，阻塞当前线程直到被打断。 */
    private static void playClipBlocking(File f) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(f)) {
            Clip c = AudioSystem.getClip();
            c.open(ais);
            clip = c;
            elapsedSamples = 0;
            sampleRate = (int) ais.getFormat().getSampleRate();
            totalSamples = (long) (c.getMicrosecondLength() / 1_000_000.0 * sampleRate);
            applyClipGain();
            c.start();
            while (running && c.isRunning()) {
                Thread.sleep(200);
                elapsedSamples = (long) (c.getMicrosecondPosition() / 1_000_000.0 * sampleRate);
            }
            c.stop();
            c.close();
        } catch (Exception ignored) {
        } finally {
            clip = null;
        }
    }

    private static void applyClipGain() {
        Clip c = clip;
        if (c == null) return;
        try {
            if (!c.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
            FloatControl fc = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
            float gain = Math.max(0, volume) / 100f;
            float db = gain <= 0.0001f ? fc.getMinimum() : (float) (20.0 * Math.log10(gain));
            fc.setValue(Math.max(fc.getMinimum(), Math.min(fc.getMaximum(), db)));
        } catch (Exception ignored) {
        }
    }

    private static void closeLine() {
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.stop();
                l.close();
            } catch (Exception ignored) {
            }
            line = null;
        }
    }

    public static synchronized void stop() {
        stopInternal();
        index = -1;
    }

    private static void stopInternal() {
        running = false;
        closeLine();
        Clip c = clip;
        if (c != null) {
            try {
                c.stop();
                c.close();
            } catch (Exception ignored) {
            }
            clip = null;
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            thread = null;
        }
    }
}
