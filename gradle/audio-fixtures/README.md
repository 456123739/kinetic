# 重建的 YSM 音频契约 fixture（非原始冻结 golden）

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
