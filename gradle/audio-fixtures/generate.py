#!/usr/bin/env python3
"""重建 YSM 音频契约 fixture 集（等价替代品，非原始冻结 golden）。

背景
----
`src/test/java` 中 8 个测试类要求环境变量 `YSM_AUDIO_FIXTURE_DIR` 指向一套冻结的
golden 音频 fixture。该 fixture 包不在 YesSteveModel 仓库、不在 YesSteveModel-Native
仓库、不在 openysm 仓库，也没有任何公开副本（见报告中的检索证据）。因此这里按测试
源码中反推出的契约**重建**一套等价 fixture：

  opus-origin.ogg      Opus 48kHz 单声道，60001 帧，且带非零 granule origin
  opus-input-rate.ogg  Opus 48kHz 单声道，60001 帧
  opus-under.ogg       Opus 48kHz 单声道，短于 4*48000 帧（保留阈值以下）
  opus-long.ogg        Opus 48kHz 单声道，长样本（循环播放用）
  vorbis-under.ogg     Vorbis 44.1kHz 单声道，176399 帧（= 4*44100-1，阈值下一帧）
  vorbis-exact.ogg     Vorbis 44.1kHz 单声道，176400 帧（= 阈值，恰好不发布 PCM）
  <name>.s16le         每个 .ogg 的参考解码 PCM（libopus / libvorbis 参考解码器）
  manifest.json        cases[]: id/encoded/reference/codec/source_channels/
                       sample_rate/frames/pre_skip/short_eligible

明确标注：这套文件是**重建**的，用于让上述测试在缺少原始冻结 golden 时仍可运行；
它验证的是“编码→容器→参考解码→被测实现”的一致性，不等价于原始冻结契约的证明力。

依赖：opusenc/opusdec（opus-tools）、oggenc/oggdec（vorbis-tools），均已解包在工作区
      tools/audio-dist 下。
"""

import json
import math
import os
import struct
import subprocess
import sys
from pathlib import Path

TOOLS = Path("/home/admin/NS/tools/audio-dist/usr/bin")
LIBDIR = Path("/home/admin/NS/tools/audio-dist/usr/lib/x86_64-linux-gnu")
OUT = Path("/home/admin/NS/audio-fixtures")

ENV = dict(os.environ)
ENV["LD_LIBRARY_PATH"] = str(LIBDIR) + ":" + ENV.get("LD_LIBRARY_PATH", "")


def run(*args):
    subprocess.run([str(a) for a in args], check=True, env=ENV,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


# ---------------------------------------------------------------- PCM source

def write_pcm(path: Path, samples: int, rate: int, channels: int = 1) -> None:
    """确定性信号：两个正弦 + 缓慢包络，便于编解码器产生真实码流。"""
    data = bytearray()
    for n in range(samples):
        t = n / rate
        envelope = 0.55 + 0.35 * math.sin(2 * math.pi * 0.7 * t)
        for channel in range(channels):
            detune = 1.0 if channel == 0 else 1.5
            value = envelope * (0.6 * math.sin(2 * math.pi * 440.0 * detune * t)
                                + 0.3 * math.sin(2 * math.pi * 1234.0 * detune * t))
            data += struct.pack("<h", max(-32768, min(32767, int(value * 20000))))
    path.write_bytes(bytes(data))


# ------------------------------------------------------------- ogg utilities

def ogg_crc(data: bytes) -> int:
    crc = 0
    for byte in data:
        crc ^= byte << 24
        crc &= 0xFFFFFFFF
        for _ in range(8):
            crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if crc & 0x80000000 \
                else (crc << 1) & 0xFFFFFFFF
    return crc


def pages(data: bytes):
    """产出 (offset, header, payload) 三元组。"""
    offset = 0
    while offset < len(data):
        assert data[offset:offset + 4] == b"OggS", "not an ogg page"
        segments = data[offset + 26]
        header_len = 27 + segments
        payload_len = sum(data[offset + 27 + i] for i in range(segments))
        end = offset + header_len + payload_len
        yield offset, data[offset:offset + header_len], data[offset + header_len:end]
        offset = end


def packets(data: bytes):
    """把 ogg 流重组为包列表（按页内分段拼接）。"""
    result, current = [], bytearray()
    for _, header, payload in pages(data):
        seg_table = header[27:]
        pos = 0
        for size in seg_table:
            current += payload[pos:pos + size]
            pos += size
            if size < 255:
                result.append(bytes(current))
                current = bytearray()
    return result


def granule_shift(path: Path, delta: int) -> None:
    """给所有页的 granule 加常量偏移，构造非零 granule origin，并重算 CRC。"""
    data = path.read_bytes()
    out = bytearray()
    for offset, header, payload in pages(data):
        page = bytearray(header) + bytearray(payload)
        granule = struct.unpack_from("<q", page, 6)[0]
        if granule >= 0:
            struct.pack_into("<q", page, 6, granule + delta)
        struct.pack_into("<I", page, 22, 0)
        struct.pack_into("<I", page, 22, ogg_crc(bytes(page)))
        out += page
    path.write_bytes(bytes(out))


def downmix_mono(path: Path) -> None:
    """按 native OpusStreamDecoder 的 Downmix 规则把参考 PCM 折成单声道。

    规则：mono[i] = nearbyint((left[i] + right[i]) / 2.0f)，即 round-half-to-even，
    与 Python 的 round() 一致。
    """
    data = path.read_bytes()
    frames = len(data) // 4
    out = bytearray()
    for i in range(frames):
        left, right = struct.unpack_from("<hh", data, i * 4)
        out += struct.pack("<h", round((left + right) / 2.0))
    path.write_bytes(bytes(out))


def opus_metadata(data: bytes):
    """按 Ogg Opus 时间轴计算 channels/rate/frames/pre_skip。

    frames = finalGranule - (origin + pre_skip)，origin 取第一个音频页的
    granule 减去该页已解码样本数；这与 SupportedAudioProbe.OpusTimeline 一致。
    """
    head = packets(data)[0]
    assert head[:8] == b"OpusHead", head[:8]
    channels = head[9]
    pre_skip = struct.unpack_from("<H", head, 10)[0]
    index = 0
    decoded = 0
    origin = None
    final = None
    current = bytearray()
    for _, header, payload in pages(data):
        pos = 0
        page_decoded = 0
        for size in header[27:]:
            current += payload[pos:pos + size]
            pos += size
            if size < 255:
                index += 1
                if index > 2:
                    page_decoded += opus_packet_frames(bytes(current))
                current = bytearray()
        if index <= 2:
            continue
        granule = struct.unpack_from("<q", header, 6)[0]
        if granule < 0:
            continue
        decoded += page_decoded
        if origin is None:
            origin = granule - decoded
        final = granule
    frames = final - (origin + pre_skip)
    return channels, 48000, frames, pre_skip


def opus_packet_frames(packet: bytes) -> int:
    toc = packet[0]
    config = toc >> 3
    code = toc & 0x3
    if config >= 16:
        samples = 120 << (config & 3)
    elif config >= 12:
        samples = 480 << (config & 1) if config < 14 else 960 << (config & 1)
    else:
        samples = (480 if config < 8 else 960) << (config & 3 if config >= 8 else 0)
    # 简化：按 spec 表计算
    table = {
        0: 480, 1: 960, 2: 1920, 3: 2880,
        4: 480, 5: 960, 6: 1920, 7: 2880,
        8: 480, 9: 960, 10: 1920, 11: 2880,
        12: 480, 13: 960, 14: 480, 15: 960,
        16: 120, 17: 240, 18: 480, 19: 960,
        20: 120, 21: 240, 22: 480, 23: 960,
        24: 120, 25: 240, 26: 480, 27: 960,
        28: 120, 29: 240, 30: 480, 31: 960,
    }
    frames = table[config]
    if code == 3:
        # 多帧包：第二个字节给出帧数
        count = packet[1] & 0x3F
        frames *= count
    return frames


def vorbis_metadata(data: bytes):
    pkts = packets(data)
    ident = pkts[0]
    assert ident[0] == 1 and ident[1:7] == b"vorbis", ident[:7]
    channels = ident[11]
    rate = struct.unpack_from("<I", ident, 12)[0]
    final = None
    for _, header, _payload in pages(data):
        granule = struct.unpack_from("<q", header, 6)[0]
        if granule > 0:
            final = granule
    return channels, rate, final


# ------------------------------------------------------------------- builder

def build():
    OUT.mkdir(parents=True, exist_ok=True)
    work = OUT / ".work"
    work.mkdir(exist_ok=True)

    cases = []
    plan = [
        # name, codec, samples, rate, shift, channels
        ("opus-origin", "opus", 60001, 48000, 1 << 20, 1),
        ("opus-input-rate", "opus", 60001, 48000, 0, 1),
        # CommonAssetViewTest / LegacySoundProjectionTest 声明为 2 声道 48000Hz 191999 帧
        ("opus-under", "opus", 191999, 48000, 0, 2),
        ("opus-long", "opus", 240000, 48000, 0, 1),
        ("vorbis-under", "vorbis", 176399, 44100, 0, 1),
        ("vorbis-exact", "vorbis", 176400, 44100, 0, 1),
    ]

    for name, codec, samples, rate, shift, channels in plan:
        pcm = work / f"{name}.s16le"
        write_pcm(pcm, samples, rate, channels)
        ogg = OUT / f"{name}.ogg"
        if codec == "opus":
            run(TOOLS / "opusenc", "--quiet", "--raw", "--raw-bits", "16",
                "--raw-chan", str(channels), "--raw-rate", str(rate), pcm, ogg)
        else:
            run(TOOLS / "oggenc", "--quiet", "--raw", "--raw-bits", "16",
                "--raw-chan", str(channels), "--raw-rate", str(rate), "-o", ogg, pcm)

        if shift:
            granule_shift(ogg, shift)

        reference = OUT / f"{name}.s16le"
        if codec == "opus":
            # opusdec 输出裸 PCM：扩展名非 .wav 即为 raw，默认 16-bit 有符号，
            # 声道数跟随码流（本 fixture 为单声道）。
            run(TOOLS / "opusdec", "--quiet", "--no-dither", "--rate", str(rate),
                ogg, reference)
            channels, srate, frames, pre_skip = opus_metadata(ogg.read_bytes())
            if channels == 2:
                # 被测 native 解码器把立体声折成单声道输出，参考 PCM 必须一致
                downmix_mono(reference)
        else:
            run(TOOLS / "oggdec", "--quiet", "--raw", "--bits", "16",
                "-o", reference, ogg)
            channels, srate, frames = vorbis_metadata(ogg.read_bytes())
            pre_skip = 0

        cases.append({
            "id": name,
            "encoded": f"{name}.ogg",
            "reference": f"{name}.s16le",
            "codec": codec,
            "source_channels": channels,
            "sample_rate": srate,
            "frames": frames,
            "pre_skip": pre_skip,
            "short_eligible": frames < 4 * srate,
        })
        print(f"{name}: channels={channels} rate={srate} frames={frames} "
              f"pre_skip={pre_skip} ref_bytes={reference.stat().st_size}")

    manifest = {"cases": cases}
    (OUT / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (OUT / "README.md").write_text(README, encoding="utf-8")


README = """# 重建的 YSM 音频契约 fixture（非原始冻结 golden）

本目录由 `generate.py` 生成，用于满足 `YSM_AUDIO_FIXTURE_DIR` 所要求的测试契约。

**这不是原始的冻结 fixture 包**：原始包不在任何公开仓库中（检索证据见交付报告）。
这里按测试源码反推出的契约重建了等价样本：

| 文件 | 契约 |
|---|---|
| `opus-origin.ogg` | Opus 48kHz 单声道 60001 帧，且带非零 granule origin |
| `opus-input-rate.ogg` | Opus 48kHz 单声道 60001 帧 |
| `opus-under.ogg` | Opus 48kHz，帧数 < 4×48000（保留阈值以下） |
| `opus-long.ogg` | Opus 48kHz 长样本（循环播放） |
| `vorbis-under.ogg` | Vorbis 44.1kHz 176399 帧（阈值下一帧） |
| `vorbis-exact.ogg` | Vorbis 44.1kHz 176400 帧（恰好阈值，不发布 PCM） |
| `*.s16le` | 对应 `.ogg` 的参考解码 PCM（libopus / libvorbis） |
| `manifest.json` | `cases[]` 元数据 |

复现：`python3 generate.py`（依赖工作区内 `tools/audio-dist` 的 opus-tools / vorbis-tools）。
"""

if __name__ == "__main__":
    build()
