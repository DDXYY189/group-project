import { MsEdgeTTS, OUTPUT_FORMAT } from "msedge-tts";
import ffmpegPath from "ffmpeg-static";
import { encode } from "silk-wasm";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { copyFile, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

const execFileAsync = promisify(execFile);

const text = process.argv[2];
const outPath = process.argv[3];
const mp3OutPath = process.argv[4];

if (!text || !outPath) {
  console.error("usage: node tts2silk.mjs <text> <out.silk>");
  process.exit(1);
}

if (typeof ffmpegPath !== "string") {
  console.error("ffmpeg-static 未提供可执行文件路径");
  process.exit(1);
}

// 微信 iLink 语音使用 16kHz 单声道 SILK v3（与微信入站语音一致）。
const SAMPLE_RATE = 16000;

const workDir = await mkdtemp(path.join(tmpdir(), "wechat-tts-"));

try {
  const tts = new MsEdgeTTS();
  await tts.setMetadata(
    "zh-CN-XiaoxiaoNeural",
    OUTPUT_FORMAT.AUDIO_24KHZ_48KBITRATE_MONO_MP3
  );
  const { audioFilePath } = await tts.toFile(workDir, text);
  tts.close();

  if (mp3OutPath) {
    await copyFile(audioFilePath, mp3OutPath);
  }

  const pcmPath = path.join(workDir, "voice.pcm");
  await execFileAsync(ffmpegPath, [
    "-y",
    "-hide_banner",
    "-loglevel",
    "error",
    "-i",
    audioFilePath,
    "-ar",
    String(SAMPLE_RATE),
    "-ac",
    "1",
    "-f",
    "s16le",
    pcmPath,
  ]);

  const pcm = await readFile(pcmPath);
  const { data, duration } = await encode(pcm, SAMPLE_RATE);

  await writeFile(outPath, data);

  console.log(
    JSON.stringify({
      ok: true,
      durationMs: duration,
      sampleRate: SAMPLE_RATE,
      bytes: data.length,
      mp3Bytes: mp3OutPath ? (await readFile(mp3OutPath)).length : 0,
    })
  );
} finally {
  await rm(workDir, { recursive: true, force: true });
}
