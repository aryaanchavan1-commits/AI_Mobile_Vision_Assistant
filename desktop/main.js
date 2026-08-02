const { app, BrowserWindow, ipcMain, net } = require("electron");
const { spawn, spawnSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const zlib = require("zlib");

const DATA_DIR = path.join(os.homedir(), ".arynox");
const MODELS_DIR = path.join(DATA_DIR, "models");
const LLAMA_DIR = path.join(DATA_DIR, "llama");
const APP_DIR = path.join(DATA_DIR, "app");

const TIERS = {
  lite: {
    llm: "qwen2.5-1.5b-instruct-q4_k_m.gguf",
    url: "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
    mm: null,
    emb: null,
    needGb: 3,
  },
  standard: {
    llm: "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
    url: "https://huggingface.co/unsloth/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
    mm: "https://huggingface.co/unsloth/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-F16.gguf",
    emb: "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q4_k_m.gguf",
    needGb: 6,
  },
  pro: {
    llm: "Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
    url: "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
    mm: "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/mmproj-F16.gguf",
    emb: "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q4_k_m.gguf",
    needGb: 8,
  },
  max: {
    llm: "Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
    url: "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
    mm: "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/mmproj-F16.gguf",
    emb: "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q4_k_m.gguf",
    needGb: 8,
  },
};

const PROVIDERS = {
  cpu: {
    win: "bin-win-cpu-x64.zip",
    linux: "bin-ubuntu-x64.tar.gz",
    label: "llama.cpp CPU (works everywhere)",
  },
  vulkan: {
    win: "bin-win-vulkan-x64.zip",
    linux: "bin-ubuntu-vulkan-x64.tar.gz",
    label: "llama.cpp Vulkan (GPU)",
  },
  cuda: {
    win: "cudart-llama-bin-win-cuda-12.4-x64.zip",
    linux: null,
    label: "llama.cpp CUDA (NVIDIA GPU)",
  },
};

const SERVER_NAME = process.platform === "win32" ? "llama-server.exe" : "llama-server";
const LLAMA_API = "http://127.0.0.1:8080";
const EMBED_API = "http://127.0.0.1:8081";

let win = null;
let serverProc = null;
let pythonProc = null;
let lastTier = "standard";
let logBuffer = [];

function log(level, message) {
  const line = `[${new Date().toLocaleTimeString()}] ${message}`;
  logBuffer.push(line);
  if (win) win.webContents.send("log", line);
  console.log(line);
}

function send(channel, data) {
  if (win) win.webContents.send(channel, data);
}

function detectTier() {
  const gb = os.totalmem() / 1e9;
  if (gb < 4) return "lite";
  if (gb < 8) return "standard";
  if (gb < 16) return "pro";
  return "max";
}

function detectProvider() {
  try {
    const r = spawnSync("nvidia-smi", ["-L"], { timeout: 10000 });
    if (r.status === 0) return "cuda";
  } catch (_) {}
  return "cpu";
}

function systemInfo() {
  return {
    platform: process.platform,
    ramGb: Math.round((os.totalmem() / 1e9) * 10) / 10,
    cpus: os.cpus().length,
    tier: detectTier(),
    provider: detectProvider(),
    providerLabel: PROVIDERS[detectProvider()].label,
    python: findPython(),
  };
}

function findPython() {
  const candidates = process.platform === "win32"
    ? ["python", "py"]
    : ["python3", "python"];
  for (const c of candidates) {
    try {
      const r = spawnSync(c, ["--version"], { timeout: 10000 });
      if (r.status === 0) return c;
    } catch (_) {}
  }
  return null;
}

function needGbFor(tier) {
  return TIERS[tier].needGb;
}

function downloadTo(url, dest, onProgress) {
  return new Promise((resolve, reject) => {
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    const existing = fs.existsSync(dest) ? fs.statSync(dest).size : 0;
    const headers = existing > 0 ? { Range: `bytes=${existing}-` } : {};
    const req = net.request({ url, headers });
    let written = existing;
    let total = 0;
    const file = fs.createWriteStream(dest, { flags: existing > 0 ? "a" : "w" });
    let redirected = 0;

    const start = () => {
      req.on("response", (resp) => {
        if (resp.statusCode >= 300 && resp.statusCode < 400 && resp.headers.location) {
          if (++redirected > 10) return reject(new Error("too many redirects"));
          file.close();
          downloadTo(resp.headers.location, dest, onProgress).then(resolve, reject);
          return;
        }
        if (resp.statusCode === 416) return resolve(dest);
        if (resp.statusCode === 206) {
          written = existing;
        } else if (resp.statusCode === 200) {
          written = 0;
          file.close();
          fs.truncateSync(dest, 0);
        } else {
          return reject(new Error(`HTTP ${resp.statusCode} for ${url}`));
        }
        total = written + Number(resp.headers["content-length"] || 0);
        resp.on("data", (chunk) => {
          written += chunk.length;
          file.write(chunk);
          if (onProgress) onProgress(written, total);
        });
        resp.on("end", () => file.end(() => resolve(dest)));
        resp.on("error", reject);
      });
      req.on("error", reject);
      req.end();
    };
    start();
  });
}

function unzip(zipPath, destDir) {
  return new Promise((resolve, reject) => {
    const { execFile } = require("child_process");
    execFile("powershell", [
      "-NoProfile",
      "-Command",
      `Expand-Archive -Path '${zipPath}' -DestinationPath '${destDir}' -Force`,
    ], { timeout: 120000 }, (err) => (err ? reject(err) : resolve()));
  });
}

function untar(tarPath, destDir) {
  return new Promise((resolve, reject) => {
    const { execFile } = require("child_process");
    execFile("tar", ["-xzf", tarPath, "-C", destDir], { timeout: 120000 }, (err) =>
      err ? reject(err) : resolve()
    );
  });
}

async function installLlama(providerArg) {
  const osKey = process.platform === "win32" ? "win" : "linux";
  let provider = providerArg;
  let pattern = PROVIDERS[provider] && PROVIDERS[provider][osKey];
  while (!pattern) {
    const fallback =
      provider === "cuda" ? "vulkan" : provider === "vulkan" ? "cpu" : null;
    if (!fallback) throw new Error(`no llama.cpp build for ${providerArg} on ${process.platform}`);
    provider = fallback;
    pattern = PROVIDERS[provider][osKey];
  }
  const exe = path.join(LLAMA_DIR, SERVER_NAME);
  if (fs.existsSync(exe)) return;
  log("info", `Provider: ${PROVIDERS[provider].label}`);
  log("info", "Fetching latest llama.cpp release...");
  const api = net.request({
    url: "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest",
    headers: { "User-Agent": "arynox-desktop" },
  });
  const assets = await new Promise((resolve, reject) => {
    api.on("response", (resp) => {
      let body = "";
      resp.on("data", (c) => (body += c));
      resp.on("end", () => {
        try {
          resolve(JSON.parse(body).assets || []);
        } catch (e) {
          reject(e);
        }
      });
    });
    api.on("error", reject);
    api.end();
  });
  const asset = assets.find((a) => a.name.endsWith(pattern));
  if (!asset) throw new Error(`no asset ${pattern}`);
  log("info", `Downloading ${asset.name}...`);
  await downloadTo(asset.browser_download_url, path.join(DATA_DIR, asset.name), (w, t) =>
    send("progress", { label: asset.name, written: w, total: t })
  );
  log("info", `Extracting ${asset.name}...`);
  fs.mkdirSync(LLAMA_DIR, { recursive: true });
  const archive = path.join(DATA_DIR, asset.name);
  if (asset.name.endsWith(".zip")) await unzip(archive, LLAMA_DIR);
  else await untar(archive, LLAMA_DIR);
  fs.rmSync(archive, { force: true });
  for (const f of walkFiles(LLAMA_DIR)) {
    if (path.basename(f) === SERVER_NAME && f !== exe) {
      fs.renameSync(f, exe);
    }
  }
  if (!fs.existsSync(exe)) throw new Error("llama-server binary not found after extract");
  log("info", "llama.cpp ready.");
}

function walkFiles(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walkFiles(p, out);
    else out.push(p);
  }
  return out;
}

async function installModels(tier) {
  const t = TIERS[tier];
  const jobs = [{ name: t.llm, url: t.url }];
  if (t.mm) jobs.push({ name: "mmproj-F16.gguf", url: t.mm });
  if (t.emb) jobs.push({ name: "bge-small-en-v1.5-q4_k_m.gguf", url: t.emb });
  fs.mkdirSync(MODELS_DIR, { recursive: true });
  for (const j of jobs) {
    log("info", `Downloading ${j.name}...`);
    await downloadTo(j.url, path.join(MODELS_DIR, j.name), (w, t) =>
      send("progress", { label: j.name, written: w, total: t })
    );
  }
}

function copyAppFiles() {
  fs.mkdirSync(APP_DIR, { recursive: true });
  const src = __dirname;
  for (const item of ["arynox", "main.py", "config.json"]) {
    const from = path.join(src, item);
    if (fs.existsSync(from)) {
      fs.cpSync(from, path.join(APP_DIR, item), { recursive: true, force: true });
    }
  }
}

function healthCheck(url, timeoutMs = 120000) {
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const tick = () => {
      if (Date.now() - started > timeoutMs) return reject(new Error("timeout waiting for " + url));
      const req = http.get(url + "/health", { timeout: 3000 }, (res) => {
        let body = "";
        res.on("data", (c) => (body += c));
        res.on("end", () => {
          if (res.statusCode === 200) return resolve(true);
          setTimeout(tick, 1000);
        });
      });
      req.on("error", () => setTimeout(tick, 1000));
      req.setTimeout(3000, () => req.destroy());
    };
    tick();
  });
}

function startLlamaServer(tier) {
  const ctx = tier === "lite" ? 2048 : tier === "standard" ? 4096 : 8192;
  const exe = path.join(LLAMA_DIR, SERVER_NAME);
  const llm = fs.readdirSync(MODELS_DIR).find(
    (f) => f.endsWith(".gguf") && f.toLowerCase().startsWith("qwen")
  );
  const mm = fs.existsSync(path.join(MODELS_DIR, "mmproj-F16.gguf"));
  const emb = fs.existsSync(path.join(MODELS_DIR, "bge-small-en-v1.5-q4_k_m.gguf"));
  const args = ["-m", path.join(MODELS_DIR, llm)];
  if (mm) args.push("--mmproj", path.join(MODELS_DIR, "mmproj-F16.gguf"));
  args.push("-c", String(ctx), "-n", "512", "--jinja", "--host", "127.0.0.1", "--port", "8080");
  serverProc = spawn(exe, args, { stdio: ["ignore", "pipe", "pipe"] });
  serverProc.stdout.on("data", (d) => log("server", d.toString().trim()));
  serverProc.stderr.on("data", (d) => log("server", d.toString().trim()));
  serverProc.on("exit", () => log("info", "llama-server stopped."));
  if (emb) {
    const args2 = ["-m", path.join(MODELS_DIR, "bge-small-en-v1.5-q4_k_m.gguf"), "--embeddings", "--pooling", "mean", "-c", "512", "--host", "127.0.0.1", "--port", "8081"];
    const embProc = spawn(exe, args2, { stdio: ["ignore", "pipe", "pipe"] });
    embProc.stderr.on("data", (d) => log("embed", d.toString().trim()));
  }
}

function ensureLocalConfig() {
  const cfgPath = path.join(DATA_DIR, "config.json");
  try {
    if (fs.existsSync(cfgPath)) {
      const cfg = JSON.parse(fs.readFileSync(cfgPath, "utf8"));
      if (cfg.local && cfg.local.enabled) return;
      cfg.local = cfg.local || {};
      cfg.local.enabled = true;
      fs.writeFileSync(cfgPath, JSON.stringify(cfg, null, 2));
    }
  } catch (_) {}
}

function startPythonBrain() {
  const py = findPython();
  if (!py) {
    log("info", "Python not found - running chat-only mode (no memory/voice).");
    return;
  }
  log("info", `Python found (${py}) - launching full Arynox brain...`);
  copyAppFiles();
  ensureLocalConfig();
  pythonProc = spawn(py, [path.join(APP_DIR, "main.py")], {
    cwd: APP_DIR,
    env: { ...process.env, ARYNOX_DIR: DATA_DIR },
    stdio: ["ignore", "pipe", "pipe"],
  });
  pythonProc.stdout.on("data", (d) => send("brain", d.toString()));
  pythonProc.stderr.on("data", (d) => send("brain", d.toString()));
  pythonProc.on("exit", (code) => {
    log("info", `Arynox brain exited (code ${code}).`);
    pythonProc = null;
  });
}

async function chatOpenAI(messages) {
  const body = JSON.stringify({ messages, temperature: 0.7, max_tokens: 256 });
  return new Promise((resolve, reject) => {
    const req = http.request(
      LLAMA_API + "/v1/chat/completions",
      { method: "POST", headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) }, timeout: 120000 },
      (res) => {
        let data = "";
        res.on("data", (c) => (data += c));
        res.on("end", () => {
          try {
            resolve(JSON.parse(data).choices[0].message.content);
          } catch (e) {
            reject(e);
          }
        });
      }
    );
    req.on("error", reject);
    req.end(body);
  });
}

function stopAll() {
  if (pythonProc) { pythonProc.kill(); pythonProc = null; }
  if (serverProc) {
    if (process.platform === "win32") spawnSync("taskkill", ["/F", "/T", "/PID", String(serverProc.pid)]);
    else { serverProc.kill(); spawnSync("pkill", ["-f", "llama-server"]); }
    serverProc = null;
  } else {
    if (process.platform === "win32") spawnSync("taskkill", ["/F", "/IM", SERVER_NAME]);
    else spawnSync("pkill", ["-f", "llama-server"]);
  }
}

ipcMain.handle("system-info", () => systemInfo());
ipcMain.handle("install", async (_e, tier) => {
  try {
    lastTier = tier;
    log("info", `Detected: ${process.platform}, RAM ${(os.totalmem() / 1e9).toFixed(1)} GB, tier=${tier}`);
    await installLlama(detectProvider());
    await installModels(tier);
    send("install-done", { tier });
    return { ok: true };
  } catch (err) {
    log("error", String(err));
    return { ok: false, error: String(err) };
  }
});
ipcMain.handle("start", async () => {
  try {
    if (!serverProc) startLlamaServer(lastTier);
    await healthCheck(LLAMA_API);
    log("info", "Local AI ready.");
    startPythonBrain();
    return { ok: true };
  } catch (err) {
    log("error", String(err));
    return { ok: false, error: String(err) };
  }
});
ipcMain.handle("stop", () => { stopAll(); return { ok: true }; });
ipcMain.handle("chat", async (_e, text) => {
  try {
    const reply = await chatOpenAI([
      { role: "system", content: "You are Arynox, a friendly concise AI companion. Answer in 2-4 short sentences." },
      { role: "user", content: text },
    ]);
    return { ok: true, mode: "llama", reply };
  } catch (err) {
    return { ok: false, error: String(err) };
  }
});
ipcMain.handle("logs", () => logBuffer.slice(-500));

function createWindow() {
  win = new BrowserWindow({
    width: 900,
    height: 680,
    title: "Arynox",
    icon: fs.existsSync(path.join(__dirname, "build", "icon.ico"))
      ? path.join(__dirname, "build", "icon.ico")
      : undefined,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  win.loadFile(path.join(__dirname, "renderer", "index.html"));
  win.on("closed", () => { win = null; });
}

app.whenReady().then(() => {
  app.setAppUserModelId("com.arynox.desktop");
  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  stopAll();
  if (process.platform !== "darwin") app.quit();
});
